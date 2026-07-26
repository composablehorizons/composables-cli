package gsc

import (
	"testing"
)

func TestSummarizeWeightsPositionByImpressions(t *testing.T) {
	summary := Summarize([]Row{
		{Clicks: 10, Impressions: 100, Position: 2},
		{Clicks: 5, Impressions: 50, Position: 8},
	})

	if summary.Clicks != 15 {
		t.Fatalf("clicks = %v", summary.Clicks)
	}
	if summary.Impressions != 150 {
		t.Fatalf("impressions = %v", summary.Impressions)
	}
	if summary.CTR != 0.1 {
		t.Fatalf("ctr = %v", summary.CTR)
	}
	if summary.Position != 4 {
		t.Fatalf("position = %v", summary.Position)
	}
}

func TestCompareRowsIncludesMissingRows(t *testing.T) {
	deltas := CompareRows(
		[]Row{{Keys: []string{"/new"}, Clicks: 4}, {Keys: []string{"/same"}, Clicks: 3}},
		[]Row{{Keys: []string{"/old"}, Clicks: 9}, {Keys: []string{"/same"}, Clicks: 1}},
	)

	found := map[string]float64{}
	for _, delta := range deltas {
		found[delta.Keys[0]] = delta.ClickDelta
	}
	if found["/new"] != 4 {
		t.Fatalf("/new delta = %v", found["/new"])
	}
	if found["/old"] != -9 {
		t.Fatalf("/old delta = %v", found["/old"])
	}
	if found["/same"] != 2 {
		t.Fatalf("/same delta = %v", found["/same"])
	}
}
