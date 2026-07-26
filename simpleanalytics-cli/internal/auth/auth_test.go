package auth

import (
	"context"
	"errors"
	"testing"
)

type fakeRunner struct {
	run func(name string, args ...string) ([]byte, error)
}

func (f fakeRunner) Run(_ context.Context, name string, args ...string) ([]byte, error) {
	return f.run(name, args...)
}

func TestResolvePrefersFlag(t *testing.T) {
	t.Setenv(EnvAPIKey, "env-key")

	manager := &Manager{
		GOOS: "darwin",
		Runner: fakeRunner{run: func(name string, args ...string) ([]byte, error) {
			t.Fatal("keychain should not be called")
			return nil, nil
		}},
	}

	key, source, err := manager.Resolve(context.Background(), "flag-key")
	if err != nil {
		t.Fatalf("Resolve() error = %v", err)
	}
	if key != "flag-key" || source != "flag" {
		t.Fatalf("Resolve() = %q, %q", key, source)
	}
}

func TestResolveFallsBackToKeychain(t *testing.T) {
	manager := &Manager{
		Service: "svc",
		Account: "acct",
		GOOS:    "darwin",
		Runner: fakeRunner{run: func(name string, args ...string) ([]byte, error) {
			if name != "security" {
				t.Fatalf("unexpected command %q", name)
			}
			return []byte("stored-key\n"), nil
		}},
	}

	key, source, err := manager.Resolve(context.Background(), "")
	if err != nil {
		t.Fatalf("Resolve() error = %v", err)
	}
	if key != "stored-key" || source != "keychain" {
		t.Fatalf("Resolve() = %q, %q", key, source)
	}
}

func TestLoadReturnsNotFound(t *testing.T) {
	manager := &Manager{
		GOOS: "darwin",
		Runner: fakeRunner{run: func(name string, args ...string) ([]byte, error) {
			return nil, errors.New("security: SecKeychainSearchCopyNext: The specified item could not be found in the keychain.")
		}},
	}

	_, err := manager.Load(context.Background())
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("Load() error = %v, want ErrNotFound", err)
	}
}
