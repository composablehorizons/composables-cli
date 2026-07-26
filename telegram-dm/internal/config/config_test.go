package config

import "testing"

func TestResolveUsesDefaults(t *testing.T) {
	cfg := Config{
		DefaultSender:    "Fibi VA",
		DefaultRecipient: "Alex",
		Senders: map[string]Sender{
			"Fibi VA": {Token: "token"},
		},
		Recipients: map[string]Recipient{
			"Alex": {ChatID: "123"},
		},
	}
	sender, recipient, err := Resolve(cfg, "", "")
	if err != nil {
		t.Fatal(err)
	}
	if sender.Token != "token" {
		t.Fatalf("token = %q, want token", sender.Token)
	}
	if recipient.ChatID != "123" {
		t.Fatalf("chat id = %q, want 123", recipient.ChatID)
	}
}

func TestResolveRejectsMissingSender(t *testing.T) {
	cfg := Empty()
	cfg.DefaultSender = "Fibi VA"
	cfg.DefaultRecipient = "Alex"
	cfg.Recipients["Alex"] = Recipient{ChatID: "123"}
	if _, _, err := Resolve(cfg, "", ""); err == nil {
		t.Fatal("expected missing sender error")
	}
}
