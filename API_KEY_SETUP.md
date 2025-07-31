# OpenAI API Key Setup Guide

This guide will help you set up your OpenAI API key securely for the TranslateOverlay app without committing it to source control.

## 🔒 Security Overview

- API keys are **only** stored in a local configuration file that is **excluded from git**
- The app **never** stores API keys in its internal settings or UI
- No API keys are ever committed to source control
- The app includes validation to ensure proper API key format
- API keys can only be configured through secure local file methods

## 📋 Prerequisites

1. **OpenAI API Key**: Get one from [OpenAI Platform](https://platform.openai.com/)
2. **Android Device**: API level 29+ (Android 10+)
3. **Internet Connection**: Required for OpenAI mode

## 🚀 Quick Setup

### Option 1: Use the Setup Script (Recommended)

1. **Make the script executable** (if needed):
   ```bash
   chmod +x setup_api_key.sh
   ```

2. **Run the setup script**:
   ```bash
   ./setup_api_key.sh
   ```

3. **Enter your API key** when prompted (it will be hidden for security)

### Option 2: Manual Setup

1. **Create a local configuration file**:
   ```bash
   # Create the file
   touch local_config.properties
   ```

2. **Edit the file** with your API key:
   ```properties
   # Local configuration - DO NOT COMMIT TO SOURCE CONTROL
   # This file contains your personal API keys and should never be shared

   # OpenAI Configuration
   openai.api.key=sk-your-actual-api-key-here
   openai.model=whisper-1
   openai.temperature=0.0
   ```

3. **Replace** `sk-your-actual-api-key-here` with your actual OpenAI API key



## 🔍 API Key Format

Your OpenAI API key should:
- Start with `sk-`
- Be followed by alphanumeric characters
- Be approximately 51 characters long
- Example: `sk-1234567890abcdef1234567890abcdef1234567890abcdef`

## 📱 Testing the Setup

1. **Build the app**:
   ```bash
   ./gradlew assembleDebug
   ```

2. **Install on device**:
   ```bash
   ./gradlew installDebug
   ```

3. **Open the app** and check the status:
   - Should show "OpenAI API: Configured ✅"
   - If not, check the error message for guidance

4. **Test transcription**:
   - Grant necessary permissions
   - Tap "Start Transcription"
   - Speak in your selected source language
   - Verify transcription appears in the overlay

## 🛠️ Troubleshooting

### "OpenAI API: No API key configured ❌"

**Solution**: You need to set up your API key using one of the methods above.

### "OpenAI API: Please replace placeholder with your API key ❌"

**Solution**: The app is using the placeholder value. Replace it with your actual API key.

### "OpenAI API: Invalid API key format ❌"

**Solution**: Check that your API key:
- Starts with `sk-`
- Contains only alphanumeric characters
- Is the correct length

### "OpenAI Error: Connection failed"

**Solutions**:
1. Check your internet connection
2. Verify your API key is valid
3. Check your OpenAI account has sufficient credits
4. Check OpenAI service status

### File Permission Issues

**Solution**: If you can't create or edit the configuration file:
```bash
# Check file permissions
ls -la local_config.properties

# Fix permissions if needed
chmod 600 local_config.properties
```

## 📁 File Locations

### Local Configuration File
- **Location**: `local_config.properties` (project root)
- **Purpose**: Stores your API key locally
- **Git Status**: Excluded from source control
- **Security**: Contains sensitive data

### Default Configuration File
- **Location**: `app/src/main/assets/default_config.properties`
- **Purpose**: Contains placeholder values
- **Git Status**: Committed to source control
- **Security**: Safe to commit (contains no real keys)

### App Storage
- **Location**: App's private directory on device
- **Purpose**: Runtime configuration storage
- **Access**: Only accessible by the app
- **Security**: Encrypted storage

## 🔄 Updating Your API Key

### Method 1: Edit Local Config File
1. Open `local_config.properties`
2. Update the `openai.api.key` value
3. Save the file
4. Restart the app



### Method 2: Use Setup Script
1. Run `./setup_api_key.sh` again
2. Enter your new API key
3. The script will update the configuration

## 🧹 Cleaning Up

### Remove API Key
```bash
# Delete the local config file
rm local_config.properties

# Or clear it from the app
# (Use the app's clear function in settings)
```

### Reset to Default
```bash
# Remove local config to use defaults
rm local_config.properties

# Rebuild and reinstall
./gradlew assembleDebug installDebug
```

## 🔐 Security Best Practices

1. **Never commit API keys** to source control
2. **Keep your API key private** and don't share it
3. **Use environment variables** in production environments
4. **Rotate your API key** periodically
5. **Monitor your API usage** in the OpenAI dashboard
6. **Use the minimum required permissions** for your API key

## 📞 Support

If you encounter issues:

1. **Check the troubleshooting section** above
2. **Verify your API key** is valid and has credits
3. **Check your internet connection**
4. **Review the app logs** using ADB:
   ```bash
   adb logcat | grep "me.connor.translateoverlay"
   ```

## 🎯 Next Steps

After setting up your API key:

1. **Test the transcription** feature
2. **Test the translation** feature
3. **Configure your preferred languages**
4. **Explore the app's settings**
5. **Monitor your OpenAI usage** and costs

---

**Remember**: Your API key is sensitive information. Keep it secure and never share it publicly! 