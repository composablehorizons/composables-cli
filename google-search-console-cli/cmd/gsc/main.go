package main

import (
	"context"
	"fmt"
	"os"

	"github.com/alexstyl/google-search-console-cli/internal/cli"
)

func main() {
	app := cli.New(os.Stdout, os.Stderr)
	if err := app.Run(context.Background(), os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}
