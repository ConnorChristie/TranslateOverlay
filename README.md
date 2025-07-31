# TranslateOverlay

An Android app that provides real-time transcription and translation overlay for device audio. The app supports both offline (Sherpa-ONNX) and online (OpenAI Realtime API) modes for speech recognition and translation.

## Features

- **Real-time Audio Transcription**: Captures device audio and transcribes it in real-time
- **Multi-language Support**: Supports multiple source and target languages
- **Dual Mode Operation**:
  - **Offline Mode**: Uses Sherpa-ONNX for local speech recognition and ML Kit for translation
  - **Online Mode**: Uses OpenAI's Realtime API for both transcription and translation
- **Floating Overlay**: Displays transcription/translation results in a floating window
- **Accessibility Service**: Captures text from other apps for translation
- **Secure API Key Storage**: Encrypts OpenAI API keys using Android's EncryptedSharedPreferences

## Supported Languages

### Source Languages (Speech Recognition)
- Chinese (中文)
- Korean (한국어)
- English
- Spanish (Español)
- French (Français)
- German (Deutsch)
- Japanese (日本語)
- Russian (Русский)

### Target Languages (Translation)
- English
- Chinese (中文)
- Korean (한국어)
- Spanish (Español)
- French (Français)
- German (Deutsch)
- Japanese (日本語)
- Russian (Русский)

## Setup Instructions

### Prerequisites
- Android 10+ (API level 29+)
- Internet connection (for OpenAI mode)
- OpenAI API key (for online mode)

### Required Permissions
1. **Overlay Permission**: Allows the app to display floating windows
2. **Accessibility Service**: Enables text capture from other apps
3. **Media Projection**: Required for audio capture
4. **Internet**: Required for OpenAI API calls

### OpenAI Setup (Optional)

To use OpenAI's realtime transcription and translation:

1. **Get an OpenAI API Key**:
   - Visit [OpenAI Platform](https://platform.openai.com/)
   - Create an account or sign in
   - Navigate to API Keys section
   - Create a new API key

2. **Configure the API Key** (Choose one method):

   **Method A: Use the setup script**
   ```bash
   chmod +x setup_api_key.sh
   ./setup_api_key.sh
   ```

   **Method B: Manual setup**
   ```bash
   cp local_config.properties.example local_config.properties
   # Edit local_config.properties and replace YOUR_OPENAI_API_KEY_HERE with your actual key
   ```

3. **Configure the App**:
   - Open the app
   - Toggle "Use OpenAI Realtime API" to enabled
   - Select your target language
   - The app will validate your API key automatically

**Note**: API keys can only be configured through the local configuration file for security. The app will never store API keys in its internal settings. See `API_KEY_SETUP.md` for detailed instructions.

3. **API Usage**:
   - OpenAI charges per API call
   - Realtime transcription uses the Whisper model
   - Translation uses GPT models
   - Monitor your usage in the OpenAI dashboard

## Usage

### Basic Usage

1. **Grant Permissions**:
   - Tap "Enable Overlay Permission" and grant the permission
   - Tap "Enable Accessibility Service" and enable the service in system settings

2. **Configure Languages**:
   - Select your source language (the language being spoken)
   - Select your target language (the language for translation)

3. **Start Transcription**:
   - Tap "Start Transcription" to begin audio capture
   - The app will request media projection permission
   - Grant permission to start real-time transcription

4. **View Results**:
   - Transcription/translation appears in a floating overlay
   - Tap "Show Overlay" to manually display the overlay
   - The overlay can be moved around the screen

### Mode Selection

#### Offline Mode (Default)
- Uses Sherpa-ONNX for speech recognition
- Uses ML Kit for translation
- Works without internet connection
- Free to use
- Limited language support compared to OpenAI

#### Online Mode (OpenAI)
- Uses OpenAI's Whisper for speech recognition
- Uses OpenAI's GPT models for translation
- Requires internet connection
- More accurate transcription and translation
- Supports more languages
- Requires OpenAI API key and usage fees

## Technical Details

### Architecture
- **MainActivity**: UI and configuration management
- **AudioCaptureService**: Handles audio capture and processing
- **OpenAIRealtimeService**: Manages OpenAI WebSocket connections
- **TranslatorService**: Provides translation services
- **FloatingOverlay**: Displays results in floating window
- **TextAccessibilityService**: Captures text from other apps

### Audio Processing
- Sample rate: 16kHz
- Format: PCM 16-bit mono
- Buffer size: Optimized for real-time processing
- Chunk size: 4KB for OpenAI API

### Security
- OpenAI API keys are encrypted using Android's EncryptedSharedPreferences
- No API keys are stored in plain text
- Network traffic uses HTTPS only

## Troubleshooting

### Common Issues

1. **"OpenAI API: Invalid or missing API key"**
   - Ensure your API key starts with "sk-"
   - Check that you've entered the key correctly
   - Verify your OpenAI account has sufficient credits

2. **"OpenAI Error: Connection failed"**
   - Check your internet connection
   - Verify the API key is valid
   - Check OpenAI service status

3. **"Missing sherpa model files"**
   - Ensure the app is properly installed
   - Reinstall the app if necessary
   - Contact support if the issue persists

4. **Audio not being captured**
   - Grant media projection permission when prompted
   - Ensure the app has overlay permission
   - Check that audio is playing on the device

### Performance Tips

1. **For better accuracy**: Use OpenAI mode when possible
2. **For offline use**: Ensure Sherpa models are properly loaded
3. **For battery life**: Use offline mode when internet is not needed
4. **For real-time performance**: Keep the overlay visible and avoid heavy background tasks

## Development

### Building from Source

1. Clone the repository
2. Open in Android Studio
3. Sync Gradle dependencies
4. Build and run on device

### Dependencies
- OkHttp: WebSocket and HTTP client
- Gson: JSON parsing
- ML Kit: Translation services
- Sherpa-ONNX: Offline speech recognition
- Material Design Components: UI components

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Support

For support, please open an issue on GitHub or contact the maintainers. 