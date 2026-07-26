package app

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os/exec"
	"strings"

	"telegram-dm/internal/config"
	"telegram-dm/internal/telegram"
)

func Run(args []string, stdout io.Writer, stderr io.Writer) int {
	if len(args) == 0 {
		printUsage(stderr)
		return 2
	}
	switch args[0] {
	case "setup":
		return runSetup(args[1:], stdout, stderr)
	case "send":
		return runSend(args[1:], stdout, stderr)
	case "status":
		return runStatus(stdout, stderr)
	case "help", "-h", "--help":
		printUsage(stdout)
		return 0
	default:
		return runSend(args, stdout, stderr)
	}
}

func runSetup(args []string, stdout io.Writer, stderr io.Writer) int {
	if len(args) == 0 {
		printSetupUsage(stderr)
		return 2
	}
	path, err := config.Path()
	if err != nil {
		fmt.Fprintf(stderr, "telegram-dm: %v\n", err)
		return 1
	}
	cfg, err := config.Load(path)
	if err != nil {
		fmt.Fprintf(stderr, "telegram-dm: %v\n", err)
		return 1
	}
	switch args[0] {
	case "sender":
		return runSetupSender(path, cfg, args[1:], stdout, stderr)
	case "recipient":
		return runSetupRecipient(path, cfg, args[1:], stdout, stderr)
	case "op":
		return runSetupOP(path, cfg, args[1:], stdout, stderr)
	default:
		printSetupUsage(stderr)
		return 2
	}
}

func runSetupSender(path string, cfg config.Config, args []string, stdout io.Writer, stderr io.Writer) int {
	fs := flag.NewFlagSet("telegram-dm setup sender", flag.ContinueOnError)
	fs.SetOutput(stderr)
	name := fs.String("name", "", "sender name")
	token := fs.String("token", "", "Telegram bot token")
	tokenRef := fs.String("token-ref", "", "1Password secret reference for Telegram bot token")
	makeDefault := fs.Bool("default", false, "make this the default sender")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *name == "" || fs.NArg() != 0 {
		printSetupSenderUsage(stderr)
		return 2
	}
	resolvedToken, err := secretValue(*token, *tokenRef)
	if err != nil {
		fmt.Fprintf(stderr, "telegram-dm: %v\n", err)
		return 1
	}
	if resolvedToken == "" {
		fmt.Fprintln(stderr, "telegram-dm: setup sender requires --token or --token-ref")
		return 2
	}
	cfg.Senders[*name] = config.Sender{Token: resolvedToken}
	if *makeDefault || cfg.DefaultSender == "" {
		cfg.DefaultSender = *name
	}
	if err := config.Save(path, cfg); err != nil {
		fmt.Fprintf(stderr, "telegram-dm: %v\n", err)
		return 1
	}
	fmt.Fprintf(stdout, "saved sender %q\n", *name)
	return 0
}

func runSetupRecipient(path string, cfg config.Config, args []string, stdout io.Writer, stderr io.Writer) int {
	fs := flag.NewFlagSet("telegram-dm setup recipient", flag.ContinueOnError)
	fs.SetOutput(stderr)
	name := fs.String("name", "", "recipient name")
	chatID := fs.String("chat-id", "", "Telegram chat id")
	chatIDRef := fs.String("chat-id-ref", "", "1Password secret reference for Telegram chat id")
	makeDefault := fs.Bool("default", false, "make this the default recipient")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *name == "" || fs.NArg() != 0 {
		printSetupRecipientUsage(stderr)
		return 2
	}
	resolvedChatID, err := secretValue(*chatID, *chatIDRef)
	if err != nil {
		fmt.Fprintf(stderr, "telegram-dm: %v\n", err)
		return 1
	}
	if resolvedChatID == "" {
		fmt.Fprintln(stderr, "telegram-dm: setup recipient requires --chat-id or --chat-id-ref")
		return 2
	}
	cfg.Recipients[*name] = config.Recipient{ChatID: resolvedChatID}
	if *makeDefault || cfg.DefaultRecipient == "" {
		cfg.DefaultRecipient = *name
	}
	if err := config.Save(path, cfg); err != nil {
		fmt.Fprintf(stderr, "telegram-dm: %v\n", err)
		return 1
	}
	fmt.Fprintf(stdout, "saved recipient %q\n", *name)
	return 0
}

func runSetupOP(path string, cfg config.Config, args []string, stdout io.Writer, stderr io.Writer) int {
	fs := flag.NewFlagSet("telegram-dm setup op", flag.ContinueOnError)
	fs.SetOutput(stderr)
	sender := fs.String("sender", "", "sender name")
	tokenRef := fs.String("token-ref", "", "1Password secret reference for Telegram bot token")
	recipient := fs.String("recipient", "", "recipient name")
	chatIDRef := fs.String("chat-id-ref", "", "1Password secret reference for Telegram chat id")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *sender == "" || *tokenRef == "" || *recipient == "" || *chatIDRef == "" || fs.NArg() != 0 {
		printSetupOPUsage(stderr)
		return 2
	}
	token, err := secretValue("", *tokenRef)
	if err != nil {
		fmt.Fprintf(stderr, "telegram-dm: %v\n", err)
		return 1
	}
	chatID, err := secretValue("", *chatIDRef)
	if err != nil {
		fmt.Fprintf(stderr, "telegram-dm: %v\n", err)
		return 1
	}
	cfg.Senders[*sender] = config.Sender{Token: token}
	cfg.Recipients[*recipient] = config.Recipient{ChatID: chatID}
	cfg.DefaultSender = *sender
	cfg.DefaultRecipient = *recipient
	if err := config.Save(path, cfg); err != nil {
		fmt.Fprintf(stderr, "telegram-dm: %v\n", err)
		return 1
	}
	fmt.Fprintf(stdout, "saved default sender %q and recipient %q\n", *sender, *recipient)
	return 0
}

func runSend(args []string, stdout io.Writer, stderr io.Writer) int {
	fs := flag.NewFlagSet("telegram-dm send", flag.ContinueOnError)
	fs.SetOutput(stderr)
	senderName := fs.String("as", "", "sender name")
	recipientName := fs.String("to", "", "recipient name")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	text := strings.TrimSpace(strings.Join(fs.Args(), " "))
	if text == "" {
		printSendUsage(stderr)
		return 2
	}
	path, err := config.Path()
	if err != nil {
		fmt.Fprintf(stderr, "telegram-dm: %v\n", err)
		return 1
	}
	cfg, err := config.Load(path)
	if err != nil {
		fmt.Fprintf(stderr, "telegram-dm: %v\n", err)
		return 1
	}
	sender, recipient, err := config.Resolve(cfg, *senderName, *recipientName)
	if err != nil {
		fmt.Fprintf(stderr, "telegram-dm: %v\n", err)
		return 1
	}
	client := telegram.NewClient(http.DefaultClient, sender.Token)
	if err := client.SendMessage(context.Background(), telegram.Message{ChatID: recipient.ChatID, Text: text}); err != nil {
		fmt.Fprintf(stderr, "telegram-dm: %v\n", err)
		return 1
	}
	fmt.Fprintln(stdout, "sent")
	return 0
}

func runStatus(stdout io.Writer, stderr io.Writer) int {
	path, err := config.Path()
	if err != nil {
		fmt.Fprintf(stderr, "telegram-dm: %v\n", err)
		return 1
	}
	cfg, err := config.Load(path)
	if err != nil {
		fmt.Fprintf(stderr, "telegram-dm: %v\n", err)
		return 1
	}
	fmt.Fprintf(stdout, "config\t%s\n", path)
	fmt.Fprintf(stdout, "default_sender\t%s\n", cfg.DefaultSender)
	fmt.Fprintf(stdout, "default_recipient\t%s\n", cfg.DefaultRecipient)
	for name := range cfg.Senders {
		fmt.Fprintf(stdout, "sender\t%s\n", name)
	}
	for name := range cfg.Recipients {
		fmt.Fprintf(stdout, "recipient\t%s\n", name)
	}
	return 0
}

func secretValue(value string, ref string) (string, error) {
	if value != "" && ref != "" {
		return "", errors.New("pass either a direct value or a 1Password ref, not both")
	}
	if value != "" {
		return value, nil
	}
	if ref == "" {
		return "", nil
	}
	output, err := exec.Command("op", "read", ref).Output()
	if err != nil {
		return "", fmt.Errorf("failed to read 1Password ref %q: %w", ref, err)
	}
	return strings.TrimSpace(string(output)), nil
}

func printUsage(w io.Writer) {
	fmt.Fprintln(w, `Usage:
  telegram-dm setup sender --name "Fibi VA" --token TOKEN --default
  telegram-dm setup recipient --name Alex --chat-id CHAT_ID --default
  telegram-dm setup op --sender "Fibi VA" --token-ref op://... --recipient Alex --chat-id-ref op://...
  telegram-dm send [--as "Fibi VA"] [--to Alex] <message>
  telegram-dm <message>
  telegram-dm status`)
}

func printSetupUsage(w io.Writer) {
	fmt.Fprintln(w, `Usage:
  telegram-dm setup sender --name "Fibi VA" --token TOKEN --default
  telegram-dm setup recipient --name Alex --chat-id CHAT_ID --default
  telegram-dm setup op --sender "Fibi VA" --token-ref op://... --recipient Alex --chat-id-ref op://...`)
}

func printSetupSenderUsage(w io.Writer) {
	fmt.Fprintln(w, `Usage:
  telegram-dm setup sender --name "Fibi VA" --token TOKEN --default
  telegram-dm setup sender --name "Fibi VA" --token-ref op://Agents/ITEM/FIELD --default`)
}

func printSetupRecipientUsage(w io.Writer) {
	fmt.Fprintln(w, `Usage:
  telegram-dm setup recipient --name Alex --chat-id CHAT_ID --default
  telegram-dm setup recipient --name Alex --chat-id-ref op://Agents/ITEM/FIELD --default`)
}

func printSetupOPUsage(w io.Writer) {
	fmt.Fprintln(w, `Usage:
  telegram-dm setup op --sender "Fibi VA" --token-ref op://Agents/ITEM/FIELD --recipient Alex --chat-id-ref op://Agents/ITEM/FIELD`)
}

func printSendUsage(w io.Writer) {
	fmt.Fprintln(w, `Usage:
  telegram-dm send [--as "Fibi VA"] [--to Alex] <message>
  telegram-dm <message>`)
}
