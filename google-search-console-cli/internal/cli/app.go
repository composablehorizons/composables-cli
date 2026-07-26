package cli

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"strings"
	"text/tabwriter"

	"github.com/alexstyl/google-search-console-cli/internal/auth"
	"github.com/alexstyl/google-search-console-cli/internal/gsc"
)

type App struct {
	stdout io.Writer
	stderr io.Writer
	auth   *auth.Manager
}

func New(stdout, stderr io.Writer) *App {
	return &App{
		stdout: stdout,
		stderr: stderr,
		auth:   auth.NewManager(),
	}
}

func (a *App) Run(ctx context.Context, args []string) error {
	if len(args) == 0 {
		a.printHelp()
		return nil
	}

	switch args[0] {
	case "auth":
		return a.runAuth(ctx, args[1:])
	case "query", "search-analytics":
		return a.runQuery(ctx, args[1:])
	case "help", "-h", "--help":
		a.printHelp()
		return nil
	default:
		return fmt.Errorf("unknown command %q", args[0])
	}
}

func (a *App) printHelp() {
	fmt.Fprint(a.stdout, `gsc: Google Search Console CLI

Usage:
  gsc auth login --credentials-path service-account.json
  gsc auth login --credentials-json '<json>'
  gsc auth status
  gsc auth logout
  gsc query --site-url sc-domain:example.com --start 2026-06-01 --end 2026-06-30
  gsc query --site-url sc-domain:example.com --start 2026-06-01 --end 2026-06-30 --dimensions page --row-limit 25000
  gsc query --site-url sc-domain:example.com --start 2026-06-01 --end 2026-06-30 --compare-start 2026-05-01 --compare-end 2026-05-31 --dimensions query

Auth resolution:
  1. --credentials-json
  2. GSC_SERVICE_ACCOUNT_JSON
  3. macOS Keychain via "gsc auth login"
`)
}

func (a *App) runAuth(ctx context.Context, args []string) error {
	if len(args) == 0 {
		return errors.New("missing auth subcommand")
	}

	switch args[0] {
	case "login":
		fs := flag.NewFlagSet("auth login", flag.ContinueOnError)
		fs.SetOutput(a.stderr)
		credentialsPath := fs.String("credentials-path", "", "Path to service account JSON")
		credentialsJSON := fs.String("credentials-json", "", "Service account JSON")
		if err := fs.Parse(args[1:]); err != nil {
			return err
		}
		credentials, err := readCredentials(*credentialsPath, *credentialsJSON)
		if err != nil {
			return err
		}
		if err := a.auth.Save(ctx, credentials); err != nil {
			return err
		}
		fmt.Fprintln(a.stdout, "Saved Google Search Console credentials to macOS Keychain.")
		return nil
	case "status":
		_, source, err := a.auth.Resolve(ctx, "")
		if err != nil {
			if errors.Is(err, auth.ErrNotFound) {
				fmt.Fprintln(a.stdout, "No credentials configured.")
				return nil
			}
			return err
		}
		fmt.Fprintf(a.stdout, "Credentials available via %s.\n", source)
		return nil
	case "logout":
		err := a.auth.Delete(ctx)
		if errors.Is(err, auth.ErrNotFound) {
			fmt.Fprintln(a.stdout, "No stored credentials found.")
			return nil
		}
		if err != nil {
			return err
		}
		fmt.Fprintln(a.stdout, "Removed stored Google Search Console credentials from macOS Keychain.")
		return nil
	default:
		return fmt.Errorf("unknown auth subcommand %q", args[0])
	}
}

func (a *App) runQuery(ctx context.Context, args []string) error {
	fs := flag.NewFlagSet("query", flag.ContinueOnError)
	fs.SetOutput(a.stderr)

	siteURL := fs.String("site-url", "", "Search Console site URL, for example sc-domain:composables.com")
	start := fs.String("start", "", "Start date (YYYY-MM-DD)")
	end := fs.String("end", "", "End date (YYYY-MM-DD)")
	dimensions := fs.String("dimensions", "", "Comma-separated dimensions, for example page,query")
	rowLimit := fs.Int("row-limit", 1000, "Maximum rows")
	startRow := fs.Int("start-row", 0, "Start row offset")
	compareStart := fs.String("compare-start", "", "Comparison start date (YYYY-MM-DD)")
	compareEnd := fs.String("compare-end", "", "Comparison end date (YYYY-MM-DD)")
	credentialsJSON := fs.String("credentials-json", "", "Service account JSON")
	asJSON := fs.Bool("json", false, "Print JSON")

	if err := fs.Parse(args); err != nil {
		return err
	}
	if strings.TrimSpace(*siteURL) == "" || strings.TrimSpace(*start) == "" || strings.TrimSpace(*end) == "" {
		return errors.New("pass --site-url, --start, and --end")
	}
	if (*compareStart == "") != (*compareEnd == "") {
		return errors.New("pass both --compare-start and --compare-end")
	}

	credentials, _, err := a.auth.Resolve(ctx, *credentialsJSON)
	if err != nil {
		if errors.Is(err, auth.ErrNotFound) {
			return errors.New("no credentials found; run `gsc auth login --credentials-path service-account.json`")
		}
		return err
	}

	client := &gsc.Client{CredentialsJSON: credentials}
	query := gsc.Query{
		SiteURL:    *siteURL,
		StartDate:  *start,
		EndDate:    *end,
		Dimensions: splitList(*dimensions),
		RowLimit:   *rowLimit,
		StartRow:   *startRow,
	}
	response, err := client.SearchAnalytics(ctx, query)
	if err != nil {
		return err
	}

	var compareResponse *gsc.Response
	if *compareStart != "" {
		compareQuery := query
		compareQuery.StartDate = *compareStart
		compareQuery.EndDate = *compareEnd
		compareResponse, err = client.SearchAnalytics(ctx, compareQuery)
		if err != nil {
			return err
		}
	}

	if *asJSON {
		return a.printJSON(*siteURL, query, response, compareQueryFrom(query, *compareStart, *compareEnd), compareResponse)
	}
	a.printText(*siteURL, query, response, compareResponse)
	return nil
}

func readCredentials(path string, explicitJSON string) ([]byte, error) {
	if strings.TrimSpace(path) != "" && strings.TrimSpace(explicitJSON) != "" {
		return nil, errors.New("use either --credentials-path or --credentials-json, not both")
	}
	if strings.TrimSpace(explicitJSON) != "" {
		return []byte(strings.TrimSpace(explicitJSON)), nil
	}
	if strings.TrimSpace(path) == "" {
		return nil, errors.New("pass --credentials-path or --credentials-json")
	}
	credentials, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if strings.TrimSpace(string(credentials)) == "" {
		return nil, errors.New("credentials file is empty")
	}
	return credentials, nil
}

func splitList(value string) []string {
	if strings.TrimSpace(value) == "" {
		return nil
	}
	parts := strings.Split(value, ",")
	result := make([]string, 0, len(parts))
	for _, part := range parts {
		trimmed := strings.TrimSpace(part)
		if trimmed != "" {
			result = append(result, trimmed)
		}
	}
	return result
}

func compareQueryFrom(query gsc.Query, start string, end string) gsc.Query {
	if start == "" || end == "" {
		return gsc.Query{}
	}
	query.StartDate = start
	query.EndDate = end
	return query
}

func (a *App) printJSON(siteURL string, query gsc.Query, response *gsc.Response, compareQuery gsc.Query, compareResponse *gsc.Response) error {
	payload := map[string]any{
		"siteUrl": siteURL,
		"range": map[string]string{
			"start": query.StartDate,
			"end":   query.EndDate,
		},
		"dimensions": query.Dimensions,
		"summary":    response.Summary,
		"rows":       response.Rows,
	}
	if compareResponse != nil {
		payload["comparison"] = map[string]any{
			"range": map[string]string{
				"start": compareQuery.StartDate,
				"end":   compareQuery.EndDate,
			},
			"summary": compareResponse.Summary,
			"rows":    compareResponse.Rows,
			"deltas":  gsc.CompareRows(response.Rows, compareResponse.Rows),
		}
	}
	encoder := json.NewEncoder(a.stdout)
	encoder.SetIndent("", "  ")
	return encoder.Encode(payload)
}

func (a *App) printText(siteURL string, query gsc.Query, response *gsc.Response, compareResponse *gsc.Response) {
	fmt.Fprintf(a.stdout, "%s %s..%s\n", siteURL, query.StartDate, query.EndDate)
	printSummary(a.stdout, response.Summary)
	if len(response.Rows) > 0 {
		printRows(a.stdout, response.Rows)
	}
	if compareResponse == nil {
		return
	}
	fmt.Fprintln(a.stdout)
	fmt.Fprintln(a.stdout, "Comparison")
	printSummary(a.stdout, compareResponse.Summary)
	deltas := gsc.CompareRows(response.Rows, compareResponse.Rows)
	if len(deltas) > 0 {
		printDeltas(a.stdout, deltas)
	}
}

func printSummary(w io.Writer, summary gsc.Metrics) {
	fmt.Fprintf(w, "Clicks: %.0f\n", summary.Clicks)
	fmt.Fprintf(w, "Impressions: %.0f\n", summary.Impressions)
	fmt.Fprintf(w, "CTR: %.3f%%\n", summary.CTR*100)
	fmt.Fprintf(w, "Position: %.2f\n", summary.Position)
}

func printRows(w io.Writer, rows []gsc.Row) {
	fmt.Fprintln(w)
	writer := tabwriter.NewWriter(w, 0, 0, 2, ' ', 0)
	fmt.Fprintln(writer, "KEYS\tCLICKS\tIMPRESSIONS\tCTR\tPOSITION")
	for _, row := range rows {
		fmt.Fprintf(writer, "%s\t%.0f\t%.0f\t%.3f%%\t%.2f\n", strings.Join(row.Keys, " | "), row.Clicks, row.Impressions, row.CTR*100, row.Position)
	}
	writer.Flush()
}

func printDeltas(w io.Writer, deltas []gsc.Delta) {
	fmt.Fprintln(w)
	writer := tabwriter.NewWriter(w, 0, 0, 2, ' ', 0)
	fmt.Fprintln(writer, "KEYS\tCLICKS\tCOMPARE\tDELTA\tIMPRESSIONS\tCOMPARE\tDELTA\tCTR DELTA\tPOSITION DELTA")
	for _, delta := range deltas {
		fmt.Fprintf(
			writer,
			"%s\t%.0f\t%.0f\t%+.0f\t%.0f\t%.0f\t%+.0f\t%+.3fpp\t%+.2f\n",
			strings.Join(delta.Keys, " | "),
			delta.Clicks,
			delta.CompareClicks,
			delta.ClickDelta,
			delta.Impressions,
			delta.CompareImpressions,
			delta.ImpressionDelta,
			delta.CTRDelta*100,
			delta.PositionDelta,
		)
	}
	writer.Flush()
}
