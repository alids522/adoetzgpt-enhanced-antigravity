import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.adoetzgpt.antigravity',
  appName: 'AdoetzGPT Antigravity',
  webDir: 'www',
  plugins: {
    StatusBar: {
      overlaysWebView: true
    }
  },
  server: {
    cleartext: true
  }
};

export default config;
