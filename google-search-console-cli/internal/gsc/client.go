package gsc

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"sort"
	"strings"

	"golang.org/x/oauth2/google"
)

const (
	DefaultBaseURL          = "https://searchconsole.googleapis.com"
	ScopeWebmastersReadonly = "https://www.googleapis.com/auth/webmasters.readonly"
)

type Client struct {
	BaseURL         string
	HTTPClient      *http.Client
	CredentialsJSON []byte
}

type Query struct {
	SiteURL    string
	StartDate  string
	EndDate    string
	Dimensions []string
	RowLimit   int
	StartRow   int
}

type Response struct {
	Rows    []Row   `json:"rows"`
	Summary Metrics `json:"summary"`
}

type Row struct {
	Keys        []string `json:"keys"`
	Clicks      float64  `json:"clicks"`
	Impressions float64  `json:"impressions"`
	CTR         float64  `json:"ctr"`
	Position    float64  `json:"position"`
}

type Metrics struct {
	Clicks      float64 `json:"clicks"`
	Impressions float64 `json:"impressions"`
	CTR         float64 `json:"ctr"`
	Position    float64 `json:"position"`
}

func (c *Client) SearchAnalytics(ctx context.Context, query Query) (*Response, error) {
	if strings.TrimSpace(query.SiteURL) == "" {
		return nil, fmt.Errorf("site URL is required")
	}
	if strings.TrimSpace(query.StartDate) == "" || strings.TrimSpace(query.EndDate) == "" {
		return nil, fmt.Errorf("start and end dates are required")
	}
	if len(c.CredentialsJSON) == 0 {
		return nil, fmt.Errorf("credentials are required")
	}

	body := map[string]any{
		"startDate": query.StartDate,
		"endDate":   query.EndDate,
	}
	if len(query.Dimensions) > 0 {
		body["dimensions"] = query.Dimensions
	}
	if query.RowLimit > 0 {
		body["rowLimit"] = query.RowLimit
	}
	if query.StartRow > 0 {
		body["startRow"] = query.StartRow
	}

	payload, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}

	httpClient, err := c.authenticatedClient(ctx)
	if err != nil {
		return nil, err
	}

	baseURL := c.BaseURL
	if baseURL == "" {
		baseURL = DefaultBaseURL
	}
	requestURL := fmt.Sprintf(
		"%s/webmasters/v3/sites/%s/searchAnalytics/query",
		strings.TrimRight(baseURL, "/"),
		url.PathEscape(query.SiteURL),
	)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, requestURL, bytes.NewReader(payload))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Content-Type", "application/json")

	resp, err := httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	responseBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode >= 400 {
		return nil, fmt.Errorf("search console request failed (%s): %s", resp.Status, strings.TrimSpace(string(responseBody)))
	}

	var decoded struct {
		Rows []Row `json:"rows"`
	}
	if err := json.Unmarshal(responseBody, &decoded); err != nil {
		return nil, fmt.Errorf("decode response: %w", err)
	}

	return &Response{
		Rows:    decoded.Rows,
		Summary: Summarize(decoded.Rows),
	}, nil
}

func (c *Client) authenticatedClient(ctx context.Context) (*http.Client, error) {
	if c.HTTPClient != nil {
		return c.HTTPClient, nil
	}
	config, err := google.JWTConfigFromJSON(c.CredentialsJSON, ScopeWebmastersReadonly)
	if err != nil {
		return nil, fmt.Errorf("parse service account credentials: %w", err)
	}
	return config.Client(ctx), nil
}

func Summarize(rows []Row) Metrics {
	var summary Metrics
	var weightedPosition float64
	for _, row := range rows {
		summary.Clicks += row.Clicks
		summary.Impressions += row.Impressions
		weightedPosition += row.Position * row.Impressions
	}
	if summary.Impressions > 0 {
		summary.CTR = summary.Clicks / summary.Impressions
		summary.Position = weightedPosition / summary.Impressions
	}
	return summary
}

type Delta struct {
	Keys               []string `json:"keys"`
	Clicks             float64  `json:"clicks"`
	CompareClicks      float64  `json:"compareClicks"`
	ClickDelta         float64  `json:"clickDelta"`
	Impressions        float64  `json:"impressions"`
	CompareImpressions float64  `json:"compareImpressions"`
	ImpressionDelta    float64  `json:"impressionDelta"`
	CTR                float64  `json:"ctr"`
	CompareCTR         float64  `json:"compareCtr"`
	CTRDelta           float64  `json:"ctrDelta"`
	Position           float64  `json:"position"`
	ComparePosition    float64  `json:"comparePosition"`
	PositionDelta      float64  `json:"positionDelta"`
}

func CompareRows(currentRows, previousRows []Row) []Delta {
	current := rowsByKey(currentRows)
	previous := rowsByKey(previousRows)
	keys := make(map[string][]string, len(current)+len(previous))
	for key, row := range current {
		keys[key] = row.Keys
	}
	for key, row := range previous {
		if _, ok := keys[key]; !ok {
			keys[key] = row.Keys
		}
	}

	deltas := make([]Delta, 0, len(keys))
	for key, parts := range keys {
		c := current[key]
		p := previous[key]
		deltas = append(deltas, Delta{
			Keys:               parts,
			Clicks:             c.Clicks,
			CompareClicks:      p.Clicks,
			ClickDelta:         c.Clicks - p.Clicks,
			Impressions:        c.Impressions,
			CompareImpressions: p.Impressions,
			ImpressionDelta:    c.Impressions - p.Impressions,
			CTR:                c.CTR,
			CompareCTR:         p.CTR,
			CTRDelta:           c.CTR - p.CTR,
			Position:           c.Position,
			ComparePosition:    p.Position,
			PositionDelta:      c.Position - p.Position,
		})
	}

	sort.SliceStable(deltas, func(i, j int) bool {
		return math.Abs(deltas[i].ClickDelta) > math.Abs(deltas[j].ClickDelta)
	})
	return deltas
}

func rowsByKey(rows []Row) map[string]Row {
	result := make(map[string]Row, len(rows))
	for _, row := range rows {
		result[strings.Join(row.Keys, "\x00")] = row
	}
	return result
}
