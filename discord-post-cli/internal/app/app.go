package app

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/alexstyl/discord-post-cli/internal/config"
	"github.com/alexstyl/discord-post-cli/internal/discord"
)

type ConfigStore interface {
	Load() (config.Config, string, error)
	Save(config.Config) (string, error)
	Clear() (string, bool, error)
	Path() (string, error)
}

type DiscordPoster interface {
	Post(ctx context.Context, webhookURL string, message string, allowEveryone bool) error
}

type Runner struct {
	Store  ConfigStore
	Client DiscordPoster
	Stdin  io.Reader
	Stdout io.Writer
	Stderr io.Writer
}

func (r Runner) Run(ctx context.Context, args []string) int {
	if len(args) == 0 {
		r.printUsage()
		return 1
	}

	switch args[0] {
	case "login":
		return r.runLogin(args[1:])
	case "post":
		return r.runPost(ctx, args[1:])
	case "status":
		return r.runStatus()
	case "logout":
		return r.runLogout()
	case "help", "--help", "-h":
		r.printUsage()
		return 0
	default:
		r.printfErr("unknown command %q\n\n", args[0])
		r.printUsage()
		return 1
	}
}

func (r Runner) runLogin(args []string) int {
	fs := flag.NewFlagSet("login", flag.ContinueOnError)
	fs.SetOutput(r.Stderr)

	webhookURL := fs.String("webhook-url", "", "Discord webhook URL to save")
	if err := fs.Parse(args); err != nil {
		return 1
	}
	if strings.TrimSpace(*webhookURL) == "" {
		r.printfErr("login requires --webhook-url\n")
		return 1
	}
	if err := discord.ValidateWebhookURL(*webhookURL); err != nil {
		r.printfErr("invalid webhook URL: %v\n", err)
		return 1
	}
	path, err := r.Store.Save(config.Config{WebhookURL: *webhookURL})
	if err != nil {
		r.printfErr("save config: %v\n", err)
		return 1
	}
	r.printf("Saved webhook to %s\n", path)
	return 0
}

func (r Runner) runPost(ctx context.Context, args []string) int {
	fs := flag.NewFlagSet("post", flag.ContinueOnError)
	fs.SetOutput(r.Stderr)

	message := fs.String("message", "", "Message content to post")
	messageFile := fs.String("message-file", "", "Path to a file whose contents will be posted")
	everyone := fs.Bool("everyone", false, "Prefix with @everyone and allow the mention")
	webhookURL := fs.String("webhook-url", "", "Discord webhook URL override for this post")

	if err := fs.Parse(args); err != nil {
		return 1
	}

	resolvedMessage, err := resolveMessageInput(*message, *messageFile, r.Stdin)
	if err != nil {
		r.printfErr("%v\n", err)
		return 1
	}
	if *everyone {
		resolvedMessage = "@everyone\n\n" + resolvedMessage
	}

	resolvedWebhookURL, err := r.resolveWebhookURL(strings.TrimSpace(*webhookURL))
	if err != nil {
		r.printfErr("%v\n", err)
		return 1
	}

	if err := r.Client.Post(ctx, resolvedWebhookURL, resolvedMessage, *everyone); err != nil {
		r.printfErr("post failed: %v\n", err)
		return 1
	}

	r.printf("Posted to Discord.\n")
	return 0
}

func (r Runner) runStatus() int {
	cfg, path, err := r.Store.Load()
	if err != nil {
		r.printfErr("load config: %v\n", err)
		return 1
	}
	if strings.TrimSpace(cfg.WebhookURL) == "" {
		r.printf("No saved webhook.\n")
		r.printf("Config path: %s\n", path)
		return 0
	}

	r.printf("Saved webhook: configured\n")
	r.printf("Config path: %s\n", path)
	return 0
}

func (r Runner) runLogout() int {
	path, removed, err := r.Store.Clear()
	if err != nil {
		r.printfErr("clear config: %v\n", err)
		return 1
	}
	if removed {
		r.printf("Removed saved webhook from %s\n", path)
	} else {
		r.printf("No saved webhook at %s\n", path)
	}
	return 0
}

func (r Runner) resolveWebhookURL(override string) (string, error) {
	if override != "" {
		if err := discord.ValidateWebhookURL(override); err != nil {
			return "", fmt.Errorf("invalid webhook URL: %w", err)
		}
		return override, nil
	}

	cfg, _, err := r.Store.Load()
	if err != nil {
		return "", fmt.Errorf("load config: %w", err)
	}
	if strings.TrimSpace(cfg.WebhookURL) == "" {
		return "", errors.New("no saved webhook; run `discord-post login --webhook-url <url>` first or pass --webhook-url")
	}
	return cfg.WebhookURL, nil
}

func (r Runner) printUsage() {
	fmt.Fprint(r.Stdout, `discord-post

Usage:
  discord-post login --webhook-url <url>
  discord-post post [--message <text> | --message-file <path>] [--everyone] [--webhook-url <url>]
  discord-post status
  discord-post logout

Notes:
  - If no message flag is provided, 'post' reads from stdin when input is piped.
  - The saved webhook is stored in your user config directory.
`)
}

func (r Runner) printf(format string, args ...any) {
	fmt.Fprintf(r.Stdout, format, args...)
}

func (r Runner) printfErr(format string, args ...any) {
	fmt.Fprintf(r.Stderr, format, args...)
}

func resolveMessageInput(message string, messageFile string, stdin io.Reader) (string, error) {
	message = strings.TrimSpace(message)
	messageFile = strings.TrimSpace(messageFile)

	switch {
	case message != "" && messageFile != "":
		return "", errors.New("specify either --message or --message-file, not both")
	case message != "":
		return message, nil
	case messageFile != "":
		data, err := os.ReadFile(messageFile)
		if err != nil {
			return "", fmt.Errorf("read message file: %w", err)
		}
		trimmed := strings.TrimSpace(string(data))
		if trimmed == "" {
			return "", errors.New("message file is blank")
		}
		return trimmed, nil
	default:
		data, err := io.ReadAll(stdin)
		if err != nil {
			return "", fmt.Errorf("read stdin: %w", err)
		}
		trimmed := strings.TrimSpace(string(data))
		if trimmed == "" {
			return "", errors.New("provide --message, --message-file, or pipe message text via stdin")
		}
		return trimmed, nil
	}
}
