package main

import (
	"context"
	"os"

	"github.com/alexstyl/discord-post-cli/internal/app"
	"github.com/alexstyl/discord-post-cli/internal/config"
	"github.com/alexstyl/discord-post-cli/internal/discord"
)

func main() {
	runner := app.Runner{
		Store:  config.NewStore(),
		Client: discord.NewClient(),
		Stdin:  os.Stdin,
		Stdout: os.Stdout,
		Stderr: os.Stderr,
	}

	os.Exit(runner.Run(context.Background(), os.Args[1:]))
}
