package discord

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const maxMessageLength = 2000

type Client struct {
	HTTPClient *http.Client
}

func NewClient() Client {
	return Client{
		HTTPClient: &http.Client{Timeout: 15 * time.Second},
	}
}

type payload struct {
	Content         string          `json:"content"`
	AllowedMentions allowedMentions `json:"allowed_mentions"`
}

type allowedMentions struct {
	Parse []string `json:"parse"`
}

func ValidateWebhookURL(value string) error {
	parsed, err := url.Parse(value)
	if err != nil {
		return fmt.Errorf("parse webhook URL: %w", err)
	}
	if parsed.Scheme != "https" {
		return fmt.Errorf("webhook URL must use https")
	}
	switch strings.ToLower(parsed.Host) {
	case "discord.com", "canary.discord.com", "ptb.discord.com":
	default:
		return fmt.Errorf("webhook URL must point to a Discord host")
	}
	if !strings.HasPrefix(parsed.Path, "/api/webhooks/") {
		return fmt.Errorf("webhook URL must use the Discord webhook path")
	}
	return nil
}

func (c Client) Post(ctx context.Context, webhookURL string, message string, allowEveryone bool) error {
	if err := ValidateWebhookURL(webhookURL); err != nil {
		return err
	}
	if strings.TrimSpace(message) == "" {
		return fmt.Errorf("message cannot be blank")
	}
	if len(message) > maxMessageLength {
		return fmt.Errorf("message exceeds Discord's %d character limit", maxMessageLength)
	}

	body, err := json.Marshal(payload{
		Content: message,
		AllowedMentions: allowedMentions{
			Parse: parseMentions(allowEveryone),
		},
	})
	if err != nil {
		return fmt.Errorf("marshal payload: %w", err)
	}

	client := c.HTTPClient
	if client == nil {
		client = http.DefaultClient
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, webhookURL, bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("post to Discord: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		return nil
	}

	responseBody, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	return fmt.Errorf("discord returned %s: %s", resp.Status, strings.TrimSpace(string(responseBody)))
}

func parseMentions(allowEveryone bool) []string {
	if allowEveryone {
		return []string{"everyone"}
	}
	return []string{}
}
