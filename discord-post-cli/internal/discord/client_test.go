package discord

import "testing"

func TestValidateWebhookURL(t *testing.T) {
	valid := []string{
		"https://discord.com/api/webhooks/123/token",
		"https://canary.discord.com/api/webhooks/123/token",
		"https://ptb.discord.com/api/webhooks/123/token",
	}
	for _, value := range valid {
		if err := ValidateWebhookURL(value); err != nil {
			t.Fatalf("ValidateWebhookURL(%q) returned error: %v", value, err)
		}
	}

	invalid := []string{
		"http://discord.com/api/webhooks/123/token",
		"https://example.com/api/webhooks/123/token",
		"https://discord.com/channels/123",
		"not-a-url",
	}
	for _, value := range invalid {
		if err := ValidateWebhookURL(value); err == nil {
			t.Fatalf("ValidateWebhookURL(%q) returned nil error", value)
		}
	}
}
