# Deepgram Java Starter

This sample demonstrates interacting with the Deepgram API from a Java server. It uses the Deepgram API with the NanoHTTPD Java package to handle API calls, and has a javascript client built from web components.

## Sign-up to Deepgram

Before you start, it's essential to generate a Deepgram API key to use in this project. [Sign-up now for Deepgram](https://console.deepgram.com/signup).

## Quickstart

### Manual

Follow these steps to get started with this starter application.

#### Clone the repository

Go to GitHub and [clone the repository](https://github.com/deepgram-starters/deepgram-python-starters).

#### Install maven

Install maven on your system. Maven is a build automation tool used primarily for Java projects.

Here's how to install maven on [Mac](https://www.baeldung.com/install-maven-on-windows-linux-mac) and [Windows](https://www.baeldung.com/install-maven-on-windows-linux-mac).

If you use homebrew, you can install maven with the following command:

```bash
brew install maven
```

#### Install dependencies

Install the project dependencies in the `Starter 01` directory.

```bash
cd ./Starter-01
mvn compile
```

#### Edit the config file

Copy the text from `.env-sample` and create a new file called `.env`. Paste in the code and enter your API key you generated in the [Deepgram console](https://console.deepgram.com/).

```bash
port=8080
deepgram_api_key=api_key
```

#### Run the application

Once running, you can [access the application in your browser](http://localhost:8080/).

```bash
mvn exec:java -Dexec.mainClass="App"
```
