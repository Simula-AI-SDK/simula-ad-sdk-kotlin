# Google Play Data Safety Guide for Simula Ad SDK

This document helps you complete the **Data Safety** section in Google Play Console when your app uses the Simula Ad SDK.

> **Important:** You must complete the Data Safety form for your app in [Google Play Console](https://play.google.com/console). This guide tells you what to declare based on Simula SDK's data practices.

---

## Quick Reference

| Data Type | Collected | Shared | Purpose |
|-----------|-----------|--------|---------|
| Messages/Text | Yes | Yes (Simula) | Advertising |
| App interactions | Yes | Yes (Simula) | Advertising, Analytics |
| Device info | Yes | Yes (Simula) | Advertising |
| Advertising ID | No | No | — |
| Location | No | No | — |
| Personal info | No | No | — |

---

## Step-by-Step Data Safety Form

### Section 1: Data Collection & Security

**Does your app collect or share any of the required user data types?**
> Yes

**Is all of the user data collected by your app encrypted in transit?**
> Yes (Simula SDK uses HTTPS for all network requests)

**Do you provide a way for users to request that their data is deleted?**
> Depends on your app. Simula SDK:
> - Session data is temporary and auto-expires
> - Contact support@simula.ad for data deletion requests
> - You should provide your own mechanism if required by GDPR/CCPA

---

### Section 2: Data Types to Declare

#### Messages (Text content)

| Question | Answer |
|----------|--------|
| Is this data collected, shared, or both? | **Both** |
| Is this data processed ephemerally? | **No** (sent to server for contextual targeting) |
| Is this data required or optional? | **Required** (needed for contextual ads) |
| Why is this data collected? | **Advertising or marketing** |

> **Note:** Conversation messages are sent to Simula's servers to determine contextually relevant ads. No messages are stored permanently or linked to user identity.

---

#### App interactions (Ad impressions, clicks)

| Question | Answer |
|----------|--------|
| Is this data collected, shared, or both? | **Both** |
| Is this data processed ephemerally? | **No** |
| Is this data required or optional? | **Required** |
| Why is this data collected? | **Advertising or marketing**, **Analytics** |

> **Details:** The SDK tracks when ads are viewed (impressions) and clicked for billing and performance measurement.

---

#### Device or other IDs (Session ID only)

| Question | Answer |
|----------|--------|
| Is this data collected, shared, or both? | **Both** |
| Is this data processed ephemerally? | **Yes** (session-scoped, temporary) |
| Is this data required or optional? | **Required** |
| Why is this data collected? | **Advertising or marketing** |

> **Note:** Simula generates temporary session IDs (NOT Android Advertising ID). These expire when the session ends and cannot be used to track users across apps.

---

### Section 3: Data NOT Collected

Explicitly select **"Not collected"** for:

- **Location** (precise or approximate)
- **Personal info** (name, email, address, phone)
- **Financial info**
- **Health and fitness**
- **Contacts**
- **Photos and videos**
- **Audio files**
- **Files and docs**
- **Calendar**
- **Web browsing history**
- **Advertising ID** (AAID/GAID)

---

## Sample Data Safety Declaration Text

Use this text in your Play Store listing's Data Safety summary:

```
This app uses the Simula Ad SDK to display contextual advertisements.

Data collected:
- Conversation context (to show relevant ads)
- Ad interaction data (impressions and clicks)
- Temporary session identifiers

Data NOT collected:
- Advertising ID (GAID)
- Location
- Personal information
- Device identifiers

All data is transmitted securely via HTTPS. Session data is temporary and
not linked to user identity. For data deletion requests, contact [your email]
or support@simula.ad.
```

---

## Data Handling Practices

### Encryption

| Practice | Status |
|----------|--------|
| Data encrypted in transit | Yes (HTTPS/TLS) |
| Data encrypted at rest | Yes (Simula servers) |

### Data Deletion

| Practice | Status |
|----------|--------|
| Users can request deletion | Yes (via support) |
| Data auto-deleted after period | Yes (session data) |

### Data Sharing

| Third Party | Data Shared | Purpose |
|-------------|-------------|---------|
| Simula Ad Network | Messages, interactions, session ID | Ad delivery |

---

## Compliance Checklist

Before submitting to Play Store, verify:

- [ ] Data Safety form completed in Play Console
- [ ] Privacy Policy updated to mention Simula SDK
- [ ] Privacy Policy URL added to Play Store listing
- [ ] User consent implemented before showing ads
- [ ] `INTERNET` permission declared in AndroidManifest.xml

---

## Additional Resources

- [Google Play Data Safety documentation](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Data types reference](https://support.google.com/googleplay/android-developer/answer/10787469#zippy=%2Cdata-types)
- [Simula Privacy Policy](https://simula.ad/privacy)

---

## Questions?

Contact admin@simula.ad for Data Safety form assistance.
