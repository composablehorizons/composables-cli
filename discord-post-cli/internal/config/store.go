package config

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
)

const appDirName = "discord-post-cli"
const configFileName = "config.json"

type Config struct {
	WebhookURL string `json:"webhook_url"`
}

type Store struct {
	UserConfigDir func() (string, error)
}

func NewStore() Store {
	return Store{
		UserConfigDir: os.UserConfigDir,
	}
}

func (s Store) Load() (Config, string, error) {
	empty := Config{}
	path, err := s.Path()
	if err != nil {
		return empty, "", err
	}

	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return empty, path, nil
	}
	if err != nil {
		return empty, path, err
	}

	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		return empty, path, err
	}
	return cfg, path, nil
}

func (s Store) Save(cfg Config) (string, error) {
	path, err := s.Path()
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return "", err
	}
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return "", err
	}
	data = append(data, '\n')
	if err := os.WriteFile(path, data, 0o600); err != nil {
		return "", err
	}
	return path, nil
}

func (s Store) Clear() (string, bool, error) {
	path, err := s.Path()
	if err != nil {
		return "", false, err
	}
	if err := os.Remove(path); errors.Is(err, os.ErrNotExist) {
		return path, false, nil
	} else if err != nil {
		return path, false, err
	}
	return path, true, nil
}

func (s Store) Path() (string, error) {
	userConfigDir := s.UserConfigDir
	if userConfigDir == nil {
		userConfigDir = os.UserConfigDir
	}
	root, err := userConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(root, appDirName, configFileName), nil
}
