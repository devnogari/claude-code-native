FROM golang:1.24-alpine AS builder

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

# Create non-root user and directories with proper permissions
RUN adduser -D -g '' ccn && \
    mkdir -p /app/uploads && \
    chown -R ccn:ccn /app
USER ccn

EXPOSE 8080

CMD ["./ccn-server"]
