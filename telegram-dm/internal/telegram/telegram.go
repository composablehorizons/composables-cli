package telegram

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
)

type Client struct {
	httpClient *http.Client
	token      string
}

type Message struct {
	ChatID string `json:"chat_id"`
	Text   string `json:"text"`
}

func NewClient(httpClient *http.Client, token string) Client {
	if httpClient == nil {
		httpClient = http.DefaultClient
	}
	return Client{httpClient: httpClient, token: token}
}

func (c Client) SendMessage(ctx context.Context, message Message) error {
	body, err := json.Marshal(message)
	if err != nil {
		return err
	}
	url := "https://api.telegram.org/bot" + c.token + "/sendMessage"
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return err
	}
	request.Header.Set("Content-Type", "application/json")
	response, err := c.httpClient.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	var result struct {
		OK          bool   `json:"ok"`
		Description string `json:"description"`
	}
	if err := json.NewDecoder(response.Body).Decode(&result); err != nil {
		return err
	}
	if !result.OK {
		if result.Description == "" {
			result.Description = response.Status
		}
		return fmt.Errorf("telegram send failed: %s", result.Description)
	}
	return nil
}
