package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

type Config struct {
	DefaultSender    string               `json:"default_sender"`
	DefaultRecipient string               `json:"default_recipient"`
	Senders          map[string]Sender    `json:"senders"`
	Recipients       map[string]Recipient `json:"recipients"`
}

type Sender struct {
	Token string `json:"token"`
}

type Recipient struct {
	ChatID string `json:"chat_id"`
}

func Empty() Config {
	return Config{
		Senders:    map[string]Sender{},
		Recipients: map[string]Recipient{},
	}
}

func Path() (string, error) {
	if path := os.Getenv("TELEGRAM_DM_CONFIG"); path != "" {
		return path, nil
	}
	base, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(base, "telegram-dm", "config.json"), nil
}

func Load(path string) (Config, error) {
	body, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return Empty(), nil
	}
	if err != nil {
		return Config{}, err
	}
	var cfg Config
	if err := json.Unmarshal(body, &cfg); err != nil {
		return Config{}, err
	}
	if cfg.Senders == nil {
		cfg.Senders = map[string]Sender{}
	}
	if cfg.Recipients == nil {
		cfg.Recipients = map[string]Recipient{}
	}
	return cfg, nil
}

func Save(path string, cfg Config) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	body, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(body, '\n'), 0o600)
}

func Resolve(cfg Config, senderName string, recipientName string) (Sender, Recipient, error) {
	if senderName == "" {
		senderName = cfg.DefaultSender
	}
	if recipientName == "" {
		recipientName = cfg.DefaultRecipient
	}
	if senderName == "" {
		return Sender{}, Recipient{}, errors.New("no sender selected; run setup sender or pass --as")
	}
	if recipientName == "" {
		return Sender{}, Recipient{}, errors.New("no recipient selected; run setup recipient or pass --to")
	}
	sender, ok := cfg.Senders[senderName]
	if !ok {
		return Sender{}, Recipient{}, fmt.Errorf("sender not found: %s", senderName)
	}
	recipient, ok := cfg.Recipients[recipientName]
	if !ok {
		return Sender{}, Recipient{}, fmt.Errorf("recipient not found: %s", recipientName)
	}
	if sender.Token == "" {
		return Sender{}, Recipient{}, fmt.Errorf("sender has no token: %s", senderName)
	}
	if recipient.ChatID == "" {
		return Sender{}, Recipient{}, fmt.Errorf("recipient has no chat id: %s", recipientName)
	}
	return sender, recipient, nil
}
