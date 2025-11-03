# AI Running Coach 🏃‍♂️

A strict, demanding AI coach for running, cross-country skiing, and triathlon training. Built with Kotlin/Ktor and Claude AI.

## Features

- **Strict Coach Personality**: No excuses, no weakness - only results
- **Multiple Sports Support**: Running, cross-country skiing, triathlon
- **Personalized Training Plans**: 4-week custom plans based on fitness level
- **Recovery Recommendations**: Nutrition, sleep, stretching, injury prevention
- **Message Limit**: 10 messages per session to encourage professional coaching

## Quick Start (Docker)

### Prerequisites

- Docker
- Docker Compose
- Anthropic API Key ([Get it here](https://console.anthropic.com/))

### Installation

1. **Clone the repository**
   ```bash
   git clone <your-repo-url>
   cd AIChallangeParty
   ```

2. **Create `.env` file**
   ```bash
   cp .env.example .env
   ```

3. **Add your Anthropic API key to `.env`**
   ```bash
   ANTHROPIC_API_KEY=your_api_key_here
   ```

4. **Start the application**
   ```bash
   docker-compose up -d
   ```

5. **Access the application**
   - Open your browser and navigate to: `http://localhost:8080`
   - The health check endpoint is available at: `http://localhost:8080/health`

### Stopping the Application

```bash
docker-compose down
```

### View Logs

```bash
docker-compose logs -f ai-coach
```

## Deployment to Ubuntu VDS

### 1. Install Docker on Ubuntu

```bash
# Update system
sudo apt update && sudo apt upgrade -y

# Install Docker
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh

# Install Docker Compose
sudo apt install docker-compose -y

# Add your user to docker group (optional)
sudo usermod -aG docker $USER
```

### 2. Clone and Deploy

```bash
# Clone the repository
git clone <your-repo-url>
cd AIChallangeParty

# Create .env file
nano .env
```

Add your API key:
```
ANTHROPIC_API_KEY=your_api_key_here
```

### 3. Build and Run

```bash
# Build and start
docker-compose up -d

# Check status
docker-compose ps

# View logs
docker-compose logs -f
```

### 4. Configure Caddy Reverse Proxy

If you're using Caddy, simply update your `Caddyfile`:

```caddy
aicoach.marketwits.ru {
    reverse_proxy localhost:8080
}
```

Reload Caddy:
```bash
sudo systemctl reload caddy
```

**Note**: Caddy automatically handles SSL certificates via Let's Encrypt. No additional configuration needed!

### Alternative: Nginx Setup

If you prefer Nginx:

```bash
sudo apt install nginx -y
```

Create Nginx config (`/etc/nginx/sites-available/ai-coach`):

```nginx
server {
    listen 80;
    server_name your-domain.com;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Enable and setup SSL:
```bash
sudo ln -s /etc/nginx/sites-available/ai-coach /etc/nginx/sites-enabled/
sudo apt install certbot python3-certbot-nginx -y
sudo certbot --nginx -d your-domain.com
sudo systemctl restart nginx
```

## API Endpoints

### Public Endpoints

- `GET /` - Main web interface
- `GET /health` - Health check endpoint
- `POST /chat` - Chat with AI coach
  ```json
  {
    "message": "I'm 25 years old, beginner, can run max 3km"
  }
  ```

### Proxy Endpoint

- `POST /api/anthropic/messages` - Proxy to Anthropic API (for internal use)

## Tools Available

The AI coach has access to three specialized tools:

1. **assess_fitness_level** - Evaluates user's fitness based on age, experience, weekly runs, max distance
2. **generate_training_plan** - Creates 4-week personalized training plan
3. **get_recovery_recommendations** - Provides nutrition, sleep, stretching, and injury prevention advice

## Tech Stack

- **Backend**: Kotlin + Ktor
- **AI**: Anthropic Claude (claude-haiku-4-5)
- **Frontend**: Vanilla JS + Tailwind CSS
- **Deployment**: Docker + Docker Compose

## Configuration

### Environment Variables

| Variable | Description | Required |
|----------|-------------|----------|
| `ANTHROPIC_API_KEY` | Your Anthropic API key | Yes |

### Ports

- Application runs on port `8080` by default
- Can be changed in `docker-compose.yml`:
  ```yaml
  ports:
    - "YOUR_PORT:8080"
  ```

## Troubleshooting

### Container won't start

```bash
# Check logs
docker-compose logs ai-coach

# Rebuild container
docker-compose down
docker-compose build --no-cache
docker-compose up -d
```

### API Key Issues

Make sure your `.env` file contains the correct API key:
```bash
cat .env
```

### Port Already in Use

If port 8080 is already in use, change it in `docker-compose.yml`:
```yaml
ports:
  - "8081:8080"  # Change 8081 to any available port
```

## Development

### Running Locally (without Docker)

```bash
# Set environment variable
export ANTHROPIC_API_KEY=your_api_key_here

# Run with Gradle
./gradlew run
```

### Building

```bash
# Build JAR
./gradlew shadowJar

# Output: build/libs/*-all.jar
```

## License

MIT

## Support

For issues and questions, please open an issue on GitHub.

---

**Note**: This AI coach has a strict, demanding personality by design. It's meant to motivate through challenge, not comfort. 💪
