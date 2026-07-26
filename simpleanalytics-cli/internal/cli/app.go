package cli

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"strings"
	"text/tabwriter"

	"github.com/alexstyl/simpleanalytics-cli/internal/auth"
	"github.com/alexstyl/simpleanalytics-cli/internal/sa"
)

const countriesPercentFetchLimit = 250

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
	case "overview":
		return a.runReport(ctx, "overview", args[1:])
	case "pages":
		return a.runReport(ctx, "pages", args[1:])
	case "referrers":
		return a.runReport(ctx, "referrers", args[1:])
	case "countries":
		return a.runReport(ctx, "countries", args[1:])
	case "events":
		return a.runReport(ctx, "events", args[1:])
	case "utm-sources":
		return a.runReport(ctx, "utm-sources", args[1:])
	case "utm-mediums":
		return a.runReport(ctx, "utm-mediums", args[1:])
	case "utm-campaigns":
		return a.runReport(ctx, "utm-campaigns", args[1:])
	case "help", "-h", "--help":
		a.printHelp()
		return nil
	default:
		return fmt.Errorf("unknown command %q", args[0])
	}
}

func (a *App) printHelp() {
	fmt.Fprint(a.stdout, `sa: Simple Analytics CLI

Usage:
  sa auth login --api-key <key>
  sa auth status
  sa auth logout
  sa overview --domain example.com --start 2026-06-29 --end 2026-07-01
  sa pages --domain example.com --start 2026-06-29 --end 2026-07-01
  sa referrers --domain example.com --start 2026-06-29 --end 2026-07-01
  sa referrers --domain example.com --path /launch-post --start 2026-06-29 --end 2026-07-01
  sa countries --domain example.com --path /launch-post --referrer linkedin.com --start 2026-06-29 --end 2026-07-01
  sa events --domain example.com --events outbound_discord_gg --start 2026-06-29 --end 2026-07-01
  sa utm-sources --domain example.com --start 2026-06-29 --end 2026-07-01

Auth resolution:
  1. --api-key
  2. SIMPLE_ANALYTICS_API_KEY
  3. macOS Keychain via "sa auth login"
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
		apiKey := fs.String("api-key", "", "Simple Analytics API key")
		if err := fs.Parse(args[1:]); err != nil {
			return err
		}
		if strings.TrimSpace(*apiKey) == "" {
			return errors.New("pass --api-key")
		}
		if err := a.auth.Save(ctx, *apiKey); err != nil {
			return err
		}
		fmt.Fprintln(a.stdout, "Saved API key to macOS Keychain.")
		return nil
	case "status":
		_, source, err := a.auth.Resolve(ctx, "")
		if err != nil {
			if errors.Is(err, auth.ErrNotFound) {
				fmt.Fprintln(a.stdout, "No API key configured.")
				return nil
			}
			return err
		}
		fmt.Fprintf(a.stdout, "API key available via %s.\n", source)
		return nil
	case "logout":
		err := a.auth.Delete(ctx)
		if errors.Is(err, auth.ErrNotFound) {
			fmt.Fprintln(a.stdout, "No stored API key found.")
			return nil
		}
		if err != nil {
			return err
		}
		fmt.Fprintln(a.stdout, "Removed stored API key from macOS Keychain.")
		return nil
	default:
		return fmt.Errorf("unknown auth subcommand %q", args[0])
	}
}

func (a *App) runReport(ctx context.Context, command string, args []string) error {
	fs := flag.NewFlagSet(command, flag.ContinueOnError)
	fs.SetOutput(a.stderr)

	domain := fs.String("domain", "", "Simple Analytics domain")
	path := fs.String("path", "", "Optional path filter, for example /blog/introducing-composables-ui")
	referrer := fs.String("referrer", "", "Optional normalized referrer filter, for example linkedin.com")
	start := fs.String("start", "", "Start date (YYYY-MM-DD)")
	end := fs.String("end", "", "End date (YYYY-MM-DD)")
	timezone := fs.String("timezone", "UTC", "Time zone")
	limit := fs.Int("limit", 20, "Maximum rows")
	events := fs.String("events", "*", "Comma-separated event names for the events command, or *")
	apiKey := fs.String("api-key", "", "Simple Analytics API key")
	asJSON := fs.Bool("json", false, "Print raw JSON")

	if err := fs.Parse(args); err != nil {
		return err
	}
	if strings.TrimSpace(*domain) == "" || strings.TrimSpace(*start) == "" || strings.TrimSpace(*end) == "" {
		return errors.New("pass --domain, --start, and --end")
	}

	resolvedKey, _, err := a.auth.Resolve(ctx, *apiKey)
	if err != nil {
		if errors.Is(err, auth.ErrNotFound) {
			return errors.New("no API key found; run `sa auth login --api-key <key>`")
		}
		return err
	}

	client := &sa.Client{APIKey: resolvedKey}
	displayLimit := *limit
	queryLimit := *limit
	if command == "countries" && queryLimit > 0 && queryLimit < countriesPercentFetchLimit {
		queryLimit = countriesPercentFetchLimit
	}

	query := sa.Query{
		Domain:   *domain,
		Path:     *path,
		Referrer: *referrer,
		Start:    *start,
		End:      *end,
		Timezone: *timezone,
		Limit:    queryLimit,
		Fields:   fieldsFor(command),
	}
	if command == "events" {
		query.Events = splitCSV(*events)
	}

	response, err := client.Fetch(ctx, query)
	if err != nil {
		return err
	}

	if command == "countries" {
		response.Countries = withDimensionPercents(response.Countries)
		response.Countries = limitDimensionRows(response.Countries, displayLimit)
	}

	if *asJSON {
		encoder := json.NewEncoder(a.stdout)
		encoder.SetIndent("", "  ")
		return encoder.Encode(response)
	}

	switch command {
	case "overview":
		a.printOverview(*domain, *start, *end, response)
	case "pages":
		a.printRows("Pages", response.Pages)
	case "referrers":
		a.printRows("Referrers", response.Referrers)
	case "countries":
		a.printRows("Countries", response.Countries)
	case "events":
		a.printEvents(response.Events)
	case "utm-sources":
		a.printRows("UTM Sources", response.UTMSources)
	case "utm-mediums":
		a.printRows("UTM Mediums", response.UTMMediums)
	case "utm-campaigns":
		a.printRows("UTM Campaigns", response.UTMCampaigns)
	}

	return nil
}

func fieldsFor(command string) []string {
	switch command {
	case "overview":
		return []string{"visitors", "pageviews"}
	case "pages":
		return []string{"pages"}
	case "referrers":
		return []string{"referrers"}
	case "countries":
		return []string{"countries"}
	case "events":
		return nil
	case "utm-sources":
		return []string{"utm_sources"}
	case "utm-mediums":
		return []string{"utm_mediums"}
	case "utm-campaigns":
		return []string{"utm_campaigns"}
	default:
		return nil
	}
}

func splitCSV(value string) []string {
	parts := strings.Split(value, ",")
	result := make([]string, 0, len(parts))
	for _, part := range parts {
		part = strings.TrimSpace(part)
		if part != "" {
			result = append(result, part)
		}
	}
	return result
}

func (a *App) printOverview(domain, start, end string, response *sa.Response) {
	fmt.Fprintf(a.stdout, "%s %s..%s\n", domain, start, end)
	if response.Visitors != nil {
		fmt.Fprintf(a.stdout, "Visitors: %d\n", *response.Visitors)
	}
	if response.Pageviews != nil {
		fmt.Fprintf(a.stdout, "Pageviews: %d\n", *response.Pageviews)
	}
}

func (a *App) printEvents(rows []sa.EventRow) {
	fmt.Fprintln(a.stdout, "Events")
	writer := tabwriter.NewWriter(a.stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(writer, "NAME\tTOTAL")
	for _, row := range rows {
		fmt.Fprintf(writer, "%s\t%d\n", fallback(row.Name), row.Total)
	}
	writer.Flush()
}

func (a *App) printRows(title string, rows []sa.DimensionRow) {
	fmt.Fprintln(a.stdout, title)
	writer := tabwriter.NewWriter(a.stdout, 0, 0, 2, ' ', 0)
	if hasDimensionPercents(rows) {
		fmt.Fprintln(writer, "VALUE\tVISITORS\tVISITOR%\tPAGEVIEWS\tPAGEVIEW%")
		for _, row := range rows {
			fmt.Fprintf(writer, "%s\t%d\t%.1f%%\t%d\t%.1f%%\n", fallback(row.Value), row.Visitors, row.VisitorPercent, row.Pageviews, row.PageviewPercent)
		}
	} else {
		fmt.Fprintln(writer, "VALUE\tVISITORS\tPAGEVIEWS")
		for _, row := range rows {
			fmt.Fprintf(writer, "%s\t%d\t%d\n", fallback(row.Value), row.Visitors, row.Pageviews)
		}
	}
	writer.Flush()
}

func withDimensionPercents(rows []sa.DimensionRow) []sa.DimensionRow {
	totalVisitors := 0
	totalPageviews := 0
	for _, row := range rows {
		totalVisitors += row.Visitors
		totalPageviews += row.Pageviews
	}

	result := make([]sa.DimensionRow, len(rows))
	for i, row := range rows {
		if totalVisitors > 0 {
			row.VisitorPercent = float64(row.Visitors) / float64(totalVisitors) * 100
		}
		if totalPageviews > 0 {
			row.PageviewPercent = float64(row.Pageviews) / float64(totalPageviews) * 100
		}
		result[i] = row
	}
	return result
}

func limitDimensionRows(rows []sa.DimensionRow, limit int) []sa.DimensionRow {
	if limit <= 0 || len(rows) <= limit {
		return rows
	}
	return rows[:limit]
}

func hasDimensionPercents(rows []sa.DimensionRow) bool {
	for _, row := range rows {
		if row.VisitorPercent != 0 || row.PageviewPercent != 0 {
			return true
		}
	}
	return false
}

func fallback(value string) string {
	if strings.TrimSpace(value) == "" {
		return "(unknown)"
	}
	return value
}
