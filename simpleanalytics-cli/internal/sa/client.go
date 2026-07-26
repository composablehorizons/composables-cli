package sa

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
)

const BaseURL = "https://simpleanalytics.com"

type Client struct {
	BaseURL    string
	HTTPClient *http.Client
	APIKey     string
}

type Query struct {
	Domain   string
	Start    string
	End      string
	Path     string
	Referrer string
	Timezone string
	Fields   []string
	Events   []string
	Limit    int
}

type Response struct {
	OK            bool           `json:"ok"`
	Error         string         `json:"error"`
	GeneratedInMS int            `json:"generated_in_ms"`
	Visitors      *int           `json:"visitors,omitempty"`
	Pageviews     *int           `json:"pageviews,omitempty"`
	Pages         []DimensionRow `json:"pages,omitempty"`
	Referrers     []DimensionRow `json:"referrers,omitempty"`
	Countries     []DimensionRow `json:"countries,omitempty"`
	UTMSources    []DimensionRow `json:"utm_sources,omitempty"`
	UTMMediums    []DimensionRow `json:"utm_mediums,omitempty"`
	UTMCampaigns  []DimensionRow `json:"utm_campaigns,omitempty"`
	Events        []EventRow     `json:"events,omitempty"`
	Raw           map[string]json.RawMessage
}

type DimensionRow struct {
	Value           string
	Visitors        int
	VisitorPercent  float64 `json:"visitor_percent,omitempty"`
	Pageviews       int
	PageviewPercent float64 `json:"pageview_percent,omitempty"`
}

type EventRow struct {
	Name  string `json:"name"`
	Total int    `json:"total"`
}

func (c *Client) Fetch(ctx context.Context, query Query) (*Response, error) {
	if strings.TrimSpace(query.Domain) == "" {
		return nil, fmt.Errorf("domain is required")
	}
	if strings.TrimSpace(query.Start) == "" || strings.TrimSpace(query.End) == "" {
		return nil, fmt.Errorf("start and end are required")
	}
	if len(query.Fields) == 0 && len(query.Events) == 0 {
		return nil, fmt.Errorf("at least one field or event is required")
	}

	baseURL := c.BaseURL
	if baseURL == "" {
		baseURL = BaseURL
	}

	requestURL, err := url.Parse(fmt.Sprintf("%s/%s.json", baseURL, query.Domain))
	if err != nil {
		return nil, err
	}

	params := requestURL.Query()
	params.Set("version", "6")
	params.Set("start", query.Start)
	params.Set("end", query.End)
	if len(query.Fields) > 0 {
		params.Set("fields", strings.Join(query.Fields, ","))
	}
	if len(query.Events) > 0 {
		params.Set("events", strings.Join(query.Events, ","))
	}
	if query.Path != "" {
		params.Set("page", query.Path)
	}
	if query.Referrer != "" {
		params.Set("referrer", query.Referrer)
	}
	if query.Timezone != "" {
		params.Set("timezone", query.Timezone)
	}
	if query.Limit > 0 {
		params.Set("limit", strconv.Itoa(query.Limit))
	}
	requestURL.RawQuery = params.Encode()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, requestURL.String(), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Content-Type", "application/json")
	if strings.TrimSpace(c.APIKey) != "" {
		req.Header.Set("Api-Key", c.APIKey)
	}

	httpClient := c.HTTPClient
	if httpClient == nil {
		httpClient = http.DefaultClient
	}
	resp, err := httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	var raw map[string]json.RawMessage
	if err := json.Unmarshal(body, &raw); err != nil {
		return nil, fmt.Errorf("decode response: %w", err)
	}

	result := &Response{Raw: raw}
	if err := json.Unmarshal(body, result); err != nil {
		return nil, fmt.Errorf("decode result: %w", err)
	}

	result.Pages = decodeDimension(raw["pages"])
	result.Referrers = decodeDimension(raw["referrers"])
	result.Countries = decodeDimension(raw["countries"])
	result.UTMSources = decodeDimension(raw["utm_sources"])
	result.UTMMediums = decodeDimension(raw["utm_mediums"])
	result.UTMCampaigns = decodeDimension(raw["utm_campaigns"])
	result.Events = decodeEvents(raw["events"])

	if resp.StatusCode >= 400 {
		if result.Error == "" {
			result.Error = resp.Status
		}
		return nil, fmt.Errorf("simple analytics request failed: %s", result.Error)
	}
	if !result.OK && result.Error != "" {
		return nil, fmt.Errorf("simple analytics request failed: %s", result.Error)
	}

	return result, nil
}

func decodeEvents(payload json.RawMessage) []EventRow {
	if len(payload) == 0 {
		return nil
	}

	var rows []EventRow
	if err := json.Unmarshal(payload, &rows); err != nil {
		return nil
	}

	sort.SliceStable(rows, func(i, j int) bool {
		if rows[i].Total == rows[j].Total {
			return rows[i].Name < rows[j].Name
		}
		return rows[i].Total > rows[j].Total
	})
	return rows
}

func decodeDimension(payload json.RawMessage) []DimensionRow {
	if len(payload) == 0 {
		return nil
	}

	var rows []map[string]any
	if err := json.Unmarshal(payload, &rows); err != nil {
		return nil
	}

	result := make([]DimensionRow, 0, len(rows))
	for _, row := range rows {
		result = append(result, DimensionRow{
			Value:     firstString(row, "value", "name", "page", "referrer", "source", "medium", "campaign"),
			Visitors:  firstInt(row, "visitors", "unique_visitors", "total"),
			Pageviews: firstInt(row, "pageviews"),
		})
	}

	sort.SliceStable(result, func(i, j int) bool {
		if result[i].Visitors == result[j].Visitors {
			return result[i].Pageviews > result[j].Pageviews
		}
		return result[i].Visitors > result[j].Visitors
	})
	return result
}

func firstString(row map[string]any, keys ...string) string {
	for _, key := range keys {
		if value, ok := row[key]; ok {
			if text, ok := value.(string); ok {
				return text
			}
		}
	}
	return ""
}

func firstInt(row map[string]any, keys ...string) int {
	for _, key := range keys {
		value, ok := row[key]
		if !ok {
			continue
		}
		switch typed := value.(type) {
		case float64:
			return int(typed)
		case int:
			return typed
		}
	}
	return 0
}
