package sa

import (
	"context"
	"io"
	"net/http"
	"strings"
	"testing"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(req *http.Request) (*http.Response, error) {
	return f(req)
}

func TestFetchBuildsExpectedRequest(t *testing.T) {
	client := &Client{
		APIKey: "test-key",
		HTTPClient: &http.Client{
			Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
				if req.Header.Get("Api-Key") != "test-key" {
					t.Fatalf("Api-Key header = %q", req.Header.Get("Api-Key"))
				}
				if req.URL.Query().Get("fields") != "pages,referrers,countries" {
					t.Fatalf("fields = %q", req.URL.Query().Get("fields"))
				}
				if req.URL.Query().Get("page") != "/launch-post" {
					t.Fatalf("page = %q", req.URL.Query().Get("page"))
				}
				if req.URL.Query().Get("referrer") != "linkedin.com" {
					t.Fatalf("referrer = %q", req.URL.Query().Get("referrer"))
				}
				if req.URL.Query().Get("limit") != "25" {
					t.Fatalf("limit = %q", req.URL.Query().Get("limit"))
				}
				return &http.Response{
					StatusCode: http.StatusOK,
					Body: io.NopCloser(strings.NewReader(`{
						"ok": true,
						"pages": [{"page":"/", "visitors": 10, "pageviews": 20}],
						"countries": [{"value":"US", "visitors": 4, "pageviews": 6}],
						"referrers": [{"referrer":"reddit.com", "visitors": 5, "pageviews": 7}]
					}`)),
					Header: make(http.Header),
				}, nil
			}),
		},
	}

	resp, err := client.Fetch(context.Background(), Query{
		Domain:   "example.com",
		Start:    "2026-06-29",
		End:      "2026-07-01",
		Path:     "/launch-post",
		Referrer: "linkedin.com",
		Timezone: "UTC",
		Fields:   []string{"pages", "referrers", "countries"},
		Limit:    25,
	})
	if err != nil {
		t.Fatalf("Fetch() error = %v", err)
	}
	if len(resp.Pages) != 1 || resp.Pages[0].Value != "/" {
		t.Fatalf("pages = %#v", resp.Pages)
	}
	if len(resp.Referrers) != 1 || resp.Referrers[0].Value != "reddit.com" {
		t.Fatalf("referrers = %#v", resp.Referrers)
	}
	if len(resp.Countries) != 1 || resp.Countries[0].Value != "US" {
		t.Fatalf("countries = %#v", resp.Countries)
	}
}

func TestFetchBuildsEventRequest(t *testing.T) {
	client := &Client{
		APIKey: "test-key",
		HTTPClient: &http.Client{
			Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
				if req.URL.Query().Get("fields") != "" {
					t.Fatalf("fields = %q", req.URL.Query().Get("fields"))
				}
				if req.URL.Query().Get("events") != "outbound_discord_gg,outbound_github_com" {
					t.Fatalf("events = %q", req.URL.Query().Get("events"))
				}
				return &http.Response{
					StatusCode: http.StatusOK,
					Body: io.NopCloser(strings.NewReader(`{
						"ok": true,
						"events": [
							{"name":"outbound_discord_gg", "total": 1},
							{"name":"outbound_github_com", "total": 454}
						]
					}`)),
					Header: make(http.Header),
				}, nil
			}),
		},
	}

	resp, err := client.Fetch(context.Background(), Query{
		Domain:   "example.com",
		Start:    "2026-06-29",
		End:      "2026-07-01",
		Timezone: "UTC",
		Events:   []string{"outbound_discord_gg", "outbound_github_com"},
	})
	if err != nil {
		t.Fatalf("Fetch() error = %v", err)
	}
	if len(resp.Events) != 2 {
		t.Fatalf("events = %#v", resp.Events)
	}
	if resp.Events[0].Name != "outbound_github_com" || resp.Events[0].Total != 454 {
		t.Fatalf("events were not sorted by total: %#v", resp.Events)
	}
}
