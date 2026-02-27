# Java Transcription Makefile
# Framework-agnostic commands for managing the project and git submodules

# Use corepack to ensure correct pnpm version
PNPM := corepack pnpm

.PHONY: help check check-prereqs install init install-backend install-frontend start-backend start-frontend start test update clean status eject-frontend

# Default target: show help
help:
	@echo "Java Transcription - Available Commands"
	@echo "========================================"
	@echo ""
	@echo "Setup:"
	@echo "  make check-prereqs     Check for required tools (git, java, mvn, pnpm)"
	@echo "  make init              Initialize submodules and install all dependencies"
	@echo "  make install-backend   Build backend JAR only"
	@echo "  make install-frontend  Install frontend dependencies only"
	@echo ""
	@echo "Development:"
	@echo "  make start             Start application (backend + frontend)"
	@echo "  make start-backend     Start backend only (port 8081)"
	@echo "  make start-frontend    Start frontend only (port 8080)"
	@echo "  make test              Run contract conformance tests"
	@echo ""
	@echo "Maintenance:"
	@echo "  make update            Update submodules to latest commits"
	@echo "  make clean             Remove build artifacts and dependencies"
	@echo "  make status            Show git and submodule status"
	@echo ""

# Check for required prerequisites
check-prereqs:
	@echo "==> Checking prerequisites..."
	@command -v git >/dev/null 2>&1 || { echo "git is required but not installed. Visit https://git-scm.com"; exit 1; }
	@command -v java >/dev/null 2>&1 || { echo "java 21+ is required but not installed. Visit https://adoptium.net"; exit 1; }
	@command -v mvn >/dev/null 2>&1 || { echo "mvn is required but not installed. Visit https://maven.apache.org"; exit 1; }
	@command -v pnpm >/dev/null 2>&1 || { echo "pnpm not found. Run: corepack enable"; exit 1; }
	@echo "All prerequisites installed"
	@echo ""

# Alias for check-prereqs (standard naming)
check: check-prereqs

# Initialize project: clone submodules and install dependencies
init: check-prereqs
	@echo "==> Initializing submodules..."
	git submodule update --init --recursive
	@echo ""
	@echo "==> Building backend (Maven)..."
	mvn clean package -q
	@echo ""
	@echo "==> Installing frontend dependencies..."
	cd frontend && $(PNPM) install
	@echo ""
	@echo "Project initialized successfully!"
	@echo ""
	@echo "Next steps:"
	@echo "  1. Copy sample.env to .env and add your DEEPGRAM_API_KEY"
	@echo "  2. Run 'make start' to start the application"
	@echo ""

# Alias for init (standard naming)
install: init

# Build backend JAR
install-backend:
	@echo "==> Building backend (Maven)..."
	mvn clean package -q

# Install frontend dependencies (requires submodule to be initialized)
install-frontend:
	@echo "==> Installing frontend dependencies..."
	@if [ ! -d "frontend" ] || [ -z "$$(ls -A frontend)" ]; then \
		echo "Error: Frontend submodule not initialized. Run 'make init' first."; \
		exit 1; \
	fi
	cd frontend && $(PNPM) install

# Start backend server (port 8081)
start-backend:
	@if [ ! -f ".env" ]; then \
		echo "Error: .env file not found. Copy sample.env to .env and add your DEEPGRAM_API_KEY"; \
		exit 1; \
	fi
	@if [ ! -f "target/java-transcription-1.0.0-shaded.jar" ]; then \
		echo "==> JAR not found, building..."; \
		mvn clean package -q; \
	fi
	@echo "==> Starting backend on http://localhost:8081"
	java -jar target/java-transcription-1.0.0-shaded.jar

# Start frontend dev server (port 8080)
start-frontend:
	@if [ ! -d "frontend" ] || [ -z "$$(ls -A frontend)" ]; then \
		echo "Error: Frontend submodule not initialized. Run 'make init' first."; \
		exit 1; \
	fi
	@echo "==> Starting frontend on http://localhost:8080"
	cd frontend && $(PNPM) run dev -- --port 8080 --no-open

# Start application (backend + frontend in parallel)
start:
	@if [ ! -f ".env" ]; then \
		echo "Error: .env file not found. Copy sample.env to .env and add your DEEPGRAM_API_KEY"; \
		exit 1; \
	fi
	@if [ ! -d "frontend" ] || [ -z "$$(ls -A frontend)" ]; then \
		echo "Error: Frontend submodule not initialized. Run 'make init' first."; \
		exit 1; \
	fi
	@echo "==> Starting application..."
	@echo "    Backend:  http://localhost:8081"
	@echo "    Frontend: http://localhost:8080"
	@echo ""
	@$(MAKE) start-backend & $(MAKE) start-frontend & wait

# Run contract conformance tests
test:
	@if [ ! -f ".env" ]; then \
		echo "Error: .env file not found. Copy sample.env to .env and add your DEEPGRAM_API_KEY"; \
		exit 1; \
	fi
	@if [ ! -d "contracts" ] || [ -z "$$(ls -A contracts)" ]; then \
		echo "Error: Contracts submodule not initialized. Run 'make init' first."; \
		exit 1; \
	fi
	@echo "==> Running contract conformance tests..."
	@bash contracts/tests/run-transcription-app.sh

# Update submodules to latest commits
update:
	@echo "==> Updating submodules..."
	git submodule update --remote --merge
	@echo "Submodules updated"

# Clean all build artifacts and dependencies
clean:
	@echo "==> Cleaning build artifacts..."
	rm -rf target
	rm -rf frontend/node_modules
	rm -rf frontend/.vite
	rm -rf frontend/dist
	@echo "Cleaned successfully"

# Show git and submodule status
status:
	@echo "==> Repository Status"
	@echo "====================="
	@echo ""
	@echo "Main Repository:"
	git status --short
	@echo ""
	@echo "Submodule Status:"
	git submodule status
	@echo ""
	@echo "Submodule Branches:"
	@cd frontend && echo "frontend: $$(git branch --show-current) ($$(git rev-parse --short HEAD))"

eject-frontend:
	@echo ""
	@echo "This will:"
	@echo "   1. Copy frontend submodule files into a regular 'frontend/' directory"
	@echo "   2. Remove the frontend git submodule configuration"
	@echo "   3. Remove the contracts git submodule"
	@echo "   4. Remove .gitmodules file"
	@echo ""
	@echo "   After ejecting, frontend changes can be committed directly"
	@echo "   with your backend changes. This cannot be undone."
	@echo ""
	@read -p "   Continue? [Y/n] " confirm; \
	if [ "$$confirm" != "Y" ] && [ "$$confirm" != "y" ] && [ -n "$$confirm" ]; then \
		echo "   Cancelled."; \
		exit 1; \
	fi
	@echo ""
	@echo "==> Ejecting frontend submodule..."
	@FRONTEND_TMP=$$(mktemp -d); \
	cp -r frontend/. "$$FRONTEND_TMP/"; \
	git submodule deinit -f frontend; \
	git rm -f frontend; \
	rm -rf .git/modules/frontend; \
	mkdir -p frontend; \
	cp -r "$$FRONTEND_TMP/." frontend/; \
	rm -rf "$$FRONTEND_TMP"; \
	rm -rf frontend/.git; \
	echo "   Frontend ejected to regular directory"
	@echo "==> Removing contracts submodule..."
	@if git config --file .gitmodules submodule.contracts.url > /dev/null 2>&1; then \
		git submodule deinit -f contracts; \
		git rm -f contracts; \
		rm -rf .git/modules/contracts; \
		echo "   Contracts submodule removed"; \
	else \
		echo "   No contracts submodule found"; \
	fi
	@if [ -f .gitmodules ] && [ ! -s .gitmodules ]; then \
		git rm -f .gitmodules; \
		echo "   Empty .gitmodules removed"; \
	fi
	@echo ""
	@echo "Eject complete! Frontend files are now regular tracked files."
	@echo "   Run 'git add . && git commit' to save the changes."
