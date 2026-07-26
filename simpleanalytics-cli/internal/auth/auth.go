package auth

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"runtime"
	"strings"
)

const (
	DefaultService = "simpleanalytics-cli"
	DefaultAccount = "default"
	EnvAPIKey      = "SIMPLE_ANALYTICS_API_KEY"
)

var ErrNotFound = errors.New("api key not found")

type Runner interface {
	Run(ctx context.Context, name string, args ...string) ([]byte, error)
}

type ExecRunner struct{}

func (ExecRunner) Run(ctx context.Context, name string, args ...string) ([]byte, error) {
	cmd := exec.CommandContext(ctx, name, args...)
	var stdout bytes.Buffer
	var stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	err := cmd.Run()
	if err != nil {
		if stderr.Len() > 0 {
			return nil, fmt.Errorf("%w: %s", err, strings.TrimSpace(stderr.String()))
		}
		return nil, err
	}
	return stdout.Bytes(), nil
}

type Manager struct {
	Service string
	Account string
	Runner  Runner
	GOOS    string
}

func NewManager() *Manager {
	return &Manager{
		Service: DefaultService,
		Account: DefaultAccount,
		Runner:  ExecRunner{},
		GOOS:    runtime.GOOS,
	}
}

func (m *Manager) Resolve(ctx context.Context, explicit string) (string, string, error) {
	if strings.TrimSpace(explicit) != "" {
		return strings.TrimSpace(explicit), "flag", nil
	}
	if value := strings.TrimSpace(os.Getenv(EnvAPIKey)); value != "" {
		return value, "env", nil
	}

	key, err := m.Load(ctx)
	if err == nil {
		return key, "keychain", nil
	}
	if errors.Is(err, ErrNotFound) {
		return "", "", ErrNotFound
	}
	return "", "", err
}

func (m *Manager) Load(ctx context.Context) (string, error) {
	if m.GOOS != "darwin" {
		return "", fmt.Errorf("persistent auth is currently implemented for macOS only")
	}
	out, err := m.Runner.Run(
		ctx,
		"security",
		"find-generic-password",
		"-s", m.Service,
		"-a", m.Account,
		"-w",
	)
	if err != nil {
		if strings.Contains(err.Error(), "could not be found") {
			return "", ErrNotFound
		}
		return "", err
	}
	value := strings.TrimSpace(string(out))
	if value == "" {
		return "", ErrNotFound
	}
	return value, nil
}

func (m *Manager) Save(ctx context.Context, apiKey string) error {
	if m.GOOS != "darwin" {
		return fmt.Errorf("persistent auth is currently implemented for macOS only")
	}
	apiKey = strings.TrimSpace(apiKey)
	if apiKey == "" {
		return errors.New("api key cannot be empty")
	}
	_, err := m.Runner.Run(
		ctx,
		"security",
		"add-generic-password",
		"-U",
		"-s", m.Service,
		"-a", m.Account,
		"-w", apiKey,
	)
	return err
}

func (m *Manager) Delete(ctx context.Context) error {
	if m.GOOS != "darwin" {
		return fmt.Errorf("persistent auth is currently implemented for macOS only")
	}
	_, err := m.Runner.Run(
		ctx,
		"security",
		"delete-generic-password",
		"-s", m.Service,
		"-a", m.Account,
	)
	if err != nil && strings.Contains(err.Error(), "could not be found") {
		return ErrNotFound
	}
	return err
}
