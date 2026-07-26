package auth

import (
	"bytes"
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"runtime"
	"strings"
)

const (
	DefaultService = "google-search-console-cli"
	DefaultAccount = "default"
	EnvCredentials = "GSC_SERVICE_ACCOUNT_JSON"
)

var ErrNotFound = errors.New("credentials not found")

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

func (m *Manager) Resolve(ctx context.Context, explicit string) ([]byte, string, error) {
	if strings.TrimSpace(explicit) != "" {
		return []byte(strings.TrimSpace(explicit)), "flag", nil
	}
	if value := strings.TrimSpace(os.Getenv(EnvCredentials)); value != "" {
		return []byte(value), "env", nil
	}

	credentials, err := m.Load(ctx)
	if err == nil {
		return credentials, "keychain", nil
	}
	if errors.Is(err, ErrNotFound) {
		return nil, "", ErrNotFound
	}
	return nil, "", err
}

func (m *Manager) Load(ctx context.Context) ([]byte, error) {
	if m.GOOS != "darwin" {
		return nil, fmt.Errorf("persistent auth is currently implemented for macOS only")
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
			return nil, ErrNotFound
		}
		return nil, err
	}
	value := strings.TrimSpace(string(out))
	if value == "" {
		return nil, ErrNotFound
	}
	decoded, err := base64.StdEncoding.DecodeString(value)
	if err == nil {
		return decoded, nil
	}
	return []byte(value), nil
}

func (m *Manager) Save(ctx context.Context, credentials []byte) error {
	if m.GOOS != "darwin" {
		return fmt.Errorf("persistent auth is currently implemented for macOS only")
	}
	trimmed := strings.TrimSpace(string(credentials))
	if trimmed == "" {
		return errors.New("credentials cannot be empty")
	}
	value := base64.StdEncoding.EncodeToString([]byte(trimmed))
	_, err := m.Runner.Run(
		ctx,
		"security",
		"add-generic-password",
		"-U",
		"-s", m.Service,
		"-a", m.Account,
		"-w", value,
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
