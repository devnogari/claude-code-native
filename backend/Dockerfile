FROM golang:1.23-alpine AS builder

WORKDIR /app

RUN apk add --no-cache gcc musl-dev

COPY go.mod go.sum ./
RUN go mod download

COPY . .

RUN CGO_ENABLED=0 GOOS=linux go build -o /ccn-server ./cmd/server

FROM alpine:3.19

RUN apk add --no-cache \
    ca-certificates \
    tzdata \
    nodejs \
    npm \
    && npm install -g @anthropic-ai/claude-code

WORKDIR /app

COPY --from=builder /ccn-server .
COPY --from=builder /app/migrations ./migrations

RUN adduser -D -g '' ccn
USER ccn

EXPOSE 8080

CMD ["./ccn-server"]
