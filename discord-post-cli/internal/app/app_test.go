package app

import (
	"bytes"
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/alexstyl/discord-post-cli/internal/config"
)

func TestResolveMessageInput(t *testing.T) {
	t.Run("message flag wins", func(t *testing.T) {
		got, err := resolveMessageInput("hello", "", strings.NewReader(""))
		if err != nil {
			t.Fatalf("resolveMessageInput returned error: %v", err)
		}
		if got != "hello" {
			t.Fatalf("got %q, want %q", got, "hello")
		}
	})

	t.Run("file and message conflict", func(t *testing.T) {
		_, err := resolveMessageInput("hello", "message.txt", strings.NewReader(""))
		if err == nil {
			t.Fatal("expected error")
		}
	})

	t.Run("stdin fallback", func(t *testing.T) {
		got, err := resolveMessageInput("", "", strings.NewReader("  from stdin \n"))
		if err != nil {
			t.Fatalf("resolveMessageInput returned error: %v", err)
		}
		if got != "from stdin" {
			t.Fatalf("got %q, want %q", got, "from stdin")
		}
	})
}

func TestRunStatusWithoutSavedWebhook(t *testing.T) {
	var stdout bytes.Buffer
	var stderr bytes.Buffer

	runner := Runner{
		Store:  testStore{},
		Stdin:  strings.NewReader(""),
		Stdout: &stdout,
		Stderr: &stderr,
	}

	exitCode := runner.Run(context.Background(), []string{"status"})
	if exitCode != 0 {
		t.Fatalf("exit code = %d, want 0", exitCode)
	}
	if !strings.Contains(stdout.String(), "No saved webhook.") {
		t.Fatalf("stdout = %q, want missing-webhook message", stdout.String())
	}
	if stderr.Len() != 0 {
		t.Fatalf("stderr = %q, want empty", stderr.String())
	}
}

func TestRunPostUsesSavedWebhook(t *testing.T) {
	var stdout bytes.Buffer
	var stderr bytes.Buffer

	client := &testClient{}
	runner := Runner{
		Store: testStore{
			cfg: config.Config{WebhookURL: "https://discord.com/api/webhooks/123/token"},
		},
		Client: client,
		Stdin:  strings.NewReader(""),
		Stdout: &stdout,
		Stderr: &stderr,
	}

	exitCode := runner.Run(context.Background(), []string{"post", "--message", "hello"})
	if exitCode != 0 {
		t.Fatalf("exit code = %d, want 0; stderr=%q", exitCode, stderr.String())
	}
	if client.webhookURL != "https://discord.com/api/webhooks/123/token" {
		t.Fatalf("webhookURL = %q", client.webhookURL)
	}
	if client.message != "hello" {
		t.Fatalf("message = %q", client.message)
	}
	if client.allowEveryone {
		t.Fatal("allowEveryone = true, want false")
	}
}

type testStore struct {
	cfg      config.Config
	loadErr  error
	clearHit bool
}

func (s testStore) Load() (config.Config, string, error) {
	return s.cfg, "/tmp/config.json", s.loadErr
}

func (s testStore) Save(cfg config.Config) (string, error) {
	return "/tmp/config.json", nil
}

func (s testStore) Clear() (string, bool, error) {
	return "/tmp/config.json", s.clearHit, nil
}

func (s testStore) Path() (string, error) {
	return "/tmp/config.json", nil
}

type testClient struct {
	webhookURL    string
	message       string
	allowEveryone bool
	err           error
}

func (c *testClient) Post(_ context.Context, webhookURL string, message string, allowEveryone bool) error {
	c.webhookURL = webhookURL
	c.message = message
	c.allowEveryone = allowEveryone
	return c.err
}

func TestRunPostSurfacesClientFailure(t *testing.T) {
	var stdout bytes.Buffer
	var stderr bytes.Buffer

	client := &testClient{err: errors.New("boom")}
	runner := Runner{
		Store: testStore{
			cfg: config.Config{WebhookURL: "https://discord.com/api/webhooks/123/token"},
		},
		Client: client,
		Stdin:  strings.NewReader(""),
		Stdout: &stdout,
		Stderr: &stderr,
	}

	exitCode := runner.Run(context.Background(), []string{"post", "--message", "hello"})
	if exitCode != 1 {
		t.Fatalf("exit code = %d, want 1", exitCode)
	}
	if !strings.Contains(stderr.String(), "post failed: boom") {
		t.Fatalf("stderr = %q", stderr.String())
	}
}
