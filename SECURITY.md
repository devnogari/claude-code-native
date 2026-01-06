# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 0.x.x   | :white_check_mark: |

## Reporting a Vulnerability

We take security vulnerabilities seriously. If you discover a security issue, please report it responsibly.

### How to Report

**Please DO NOT open a public GitHub issue for security vulnerabilities.**

Instead, please report security vulnerabilities by emailing:

- **Email**: security@devnogari.com

### What to Include

Please include the following information in your report:

1. **Description**: A clear description of the vulnerability
2. **Steps to Reproduce**: Detailed steps to reproduce the issue
3. **Impact**: What an attacker could achieve by exploiting this
4. **Affected Versions**: Which versions are affected
5. **Suggested Fix**: If you have one (optional)

### What to Expect

- **Acknowledgment**: We will acknowledge receipt within 48 hours
- **Initial Assessment**: We will provide an initial assessment within 7 days
- **Resolution Timeline**: We aim to resolve critical issues within 30 days
- **Credit**: We will credit you in our release notes (unless you prefer anonymity)

### Safe Harbor

We support safe harbor for security researchers who:

- Make a good faith effort to avoid privacy violations
- Do not access or modify other users' data
- Do not disrupt our services
- Report vulnerabilities responsibly

## Security Best Practices for Users

### Environment Variables

Never commit sensitive values. Use `.env` files locally:

```bash
# Required secrets (never commit these)
DB_PASSWORD=your-secure-password
JWT_SECRET=your-jwt-secret-minimum-32-characters
```

### JWT Secret Requirements

- Minimum 32 characters
- Use cryptographically random values
- Rotate periodically in production

### Database Security

- Use strong passwords
- Limit network access to trusted sources
- Enable SSL/TLS for database connections in production

## Known Security Considerations

### Authentication

- JWT tokens with configurable expiration
- Password hashing using bcrypt
- Session management with secure token storage

### WebSocket Security

- JWT authentication required for WebSocket connections
- Per-conversation authorization checks

## Updates

This security policy may be updated periodically. Please check back for the latest guidelines.
