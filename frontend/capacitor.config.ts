import type { CapacitorConfig } from '@capacitor/cli';

/**
 * Capacitor Android shell.
 *
 * `server.url` mode: the WebView loads your live TomSense instance directly,
 * so frontend changes ship without rebuilding the APK. The bundled `webDir`
 * assets only serve as an offline fallback.
 *
 * The instance URL is baked in at build time from TOMSENSE_SERVER_URL:
 *
 *     TOMSENSE_SERVER_URL=https://tomsense.your-domain.com npx cap sync android
 *
 * then build the APK as usual (or trigger the android-build.yml workflow,
 * which reads the same variable from a repo secret/variable). Unset, it
 * falls back to the compose stack's default local address.
 *
 * `allowNavigation` keeps the Cloudflare Access + Google sign-in redirects
 * inside the WebView instead of bouncing out to an external browser (which
 * would break the auth cookie handoff).
 */
const serverUrl = process.env.TOMSENSE_SERVER_URL || 'http://localhost:8002';
const serverHost = new URL(serverUrl).hostname;

const config: CapacitorConfig = {
  appId: 'com.tomsense.app',
  appName: 'TomSense',
  webDir: 'build',
  server: {
    url: serverUrl,
    androidScheme: 'https',
    allowNavigation: [
      serverHost,
      '*.cloudflareaccess.com',
      'accounts.google.com'
    ]
  },
  android: {
    // Voice features rely on getUserMedia inside the WebView; the native
    // RECORD_AUDIO permission is declared in AndroidManifest.xml.
    allowMixedContent: false
  }
};

export default config;
