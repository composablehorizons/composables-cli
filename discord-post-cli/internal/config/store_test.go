package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestStoreSaveLoadClear(t *testing.T) {
	root := t.TempDir()
	store := Store{
		UserConfigDir: func() (string, error) {
			return root, nil
		},
	}

	path, err := store.Save(Config{WebhookURL: "https://discord.com/api/webhooks/123/token"})
	if err != nil {
		t.Fatalf("Save returned error: %v", err)
	}
	wantPath := filepath.Join(root, appDirName, configFileName)
	if path != wantPath {
		t.Fatalf("path = %q, want %q", path, wantPath)
	}

	cfg, loadedPath, err := store.Load()
	if err != nil {
		t.Fatalf("Load returned error: %v", err)
	}
	if loadedPath != wantPath {
		t.Fatalf("loadedPath = %q, want %q", loadedPath, wantPath)
	}
	if cfg.WebhookURL != "https://discord.com/api/webhooks/123/token" {
		t.Fatalf("cfg.WebhookURL = %q", cfg.WebhookURL)
	}

	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("Stat returned error: %v", err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("permissions = %#o, want %#o", info.Mode().Perm(), 0o600)
	}

	clearedPath, removed, err := store.Clear()
	if err != nil {
		t.Fatalf("Clear returned error: %v", err)
	}
	if clearedPath != wantPath {
		t.Fatalf("clearedPath = %q, want %q", clearedPath, wantPath)
	}
	if !removed {
		t.Fatal("removed = false, want true")
	}
}
