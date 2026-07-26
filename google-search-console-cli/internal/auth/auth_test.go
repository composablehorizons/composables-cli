package auth

import (
	"context"
	"encoding/base64"
	"errors"
	"os"
	"testing"
)

type fakeRunner struct {
	output []byte
	err    error
	calls  [][]string
}

func (r *fakeRunner) Run(_ context.Context, name string, args ...string) ([]byte, error) {
	r.calls = append(r.calls, append([]string{name}, args...))
	return r.output, r.err
}

func TestResolvePrefersExplicitCredentials(t *testing.T) {
	manager := &Manager{Runner: &fakeRunner{}, GOOS: "darwin"}

	got, source, err := manager.Resolve(context.Background(), `{"client_email":"x"}`)
	if err != nil {
		t.Fatalf("Resolve returned error: %v", err)
	}
	if string(got) != `{"client_email":"x"}` {
		t.Fatalf("credentials = %q", got)
	}
	if source != "flag" {
		t.Fatalf("source = %q", source)
	}
}

func TestResolveUsesEnvironment(t *testing.T) {
	t.Setenv(EnvCredentials, `{"private_key":"secret"}`)
	manager := &Manager{Runner: &fakeRunner{}, GOOS: "darwin"}

	got, source, err := manager.Resolve(context.Background(), "")
	if err != nil {
		t.Fatalf("Resolve returned error: %v", err)
	}
	if string(got) != os.Getenv(EnvCredentials) {
		t.Fatalf("credentials = %q", got)
	}
	if source != "env" {
		t.Fatalf("source = %q", source)
	}
}

func TestLoadMapsMissingKeychainItem(t *testing.T) {
	manager := &Manager{
		Runner: &fakeRunner{err: errors.New("security: SecKeychainSearchCopyNext: The specified item could not be found in the keychain.")},
		GOOS:   "darwin",
	}

	_, err := manager.Load(context.Background())
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("err = %v, want ErrNotFound", err)
	}
}

func TestLoadDecodesBase64Credentials(t *testing.T) {
	credentials := `{"type":"service_account","private_key":"line1\nline2"}`
	manager := &Manager{
		Runner: &fakeRunner{output: []byte(base64.StdEncoding.EncodeToString([]byte(credentials)))},
		GOOS:   "darwin",
	}

	got, err := manager.Load(context.Background())
	if err != nil {
		t.Fatalf("Load returned error: %v", err)
	}
	if string(got) != credentials {
		t.Fatalf("credentials = %q", got)
	}
}
