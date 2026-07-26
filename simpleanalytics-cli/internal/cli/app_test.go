package cli

import (
	"math"
	"testing"

	"github.com/alexstyl/simpleanalytics-cli/internal/sa"
)

func TestWithDimensionPercentsUsesAllRowsAsDenominator(t *testing.T) {
	rows := []sa.DimensionRow{
		{Value: "us", Visitors: 12, Pageviews: 100},
		{Value: "in", Visitors: 8, Pageviews: 50},
		{Value: "de", Visitors: 5, Pageviews: 50},
	}

	result := withDimensionPercents(rows)

	assertClose(t, result[0].VisitorPercent, 48)
	assertClose(t, result[1].VisitorPercent, 32)
	assertClose(t, result[2].VisitorPercent, 20)
	assertClose(t, result[0].PageviewPercent, 50)
	assertClose(t, result[1].PageviewPercent, 25)
	assertClose(t, result[2].PageviewPercent, 25)
}

func TestLimitDimensionRows(t *testing.T) {
	rows := []sa.DimensionRow{
		{Value: "us"},
		{Value: "in"},
		{Value: "de"},
	}

	result := limitDimensionRows(rows, 2)

	if len(result) != 2 {
		t.Fatalf("expected 2 rows, got %d", len(result))
	}
	if result[0].Value != "us" || result[1].Value != "in" {
		t.Fatalf("unexpected rows: %+v", result)
	}
}

func assertClose(t *testing.T, got, want float64) {
	t.Helper()
	if math.Abs(got-want) > 0.0001 {
		t.Fatalf("expected %.4f, got %.4f", want, got)
	}
}
