
cat > docs/ARCHITECTURE.md <<'EOF'
# ZOI — System Architecture

Version: 0.2
Date: 2026-11-17
Status: Initial product architecture

## 1. Product definition

Zoi is a voice-first personal AI companion with an interactive
robotic character.

It helps users communicate naturally, obtain information,
manage personal activities and perform approved actions on
their Android devices.

Zoi also provides responsible emotional support when requested.

Zoi is not a licensed therapist, diagnostic tool or emergency
service.

## 2. Product principles

- Voice-first, with text as an alternative.
- Natural, concise communication.
- Useful actions rather than conversation alone.
- Context-aware assistance.
- Optional, consent-based memory.
- A recognisable and expressive personality.
- Humour when appropriate.
- Transparent permissions and action execution.
- Privacy and safety by design.

## 3. High-level architecture

Zoi consists of two primary systems.

### A. Zoi Companion

The Android application is responsible for:
- Floating robotic character.
- Character animation.
- Voice input and output.
- Text conversation interface.
- Device integrations.
- Permission management.
- Action execution and feedback.
- User settings and accessibility.

### B. Zoi Core

The Python backend is responsible for:
- Conversation intelligence.
- Intent and skill routing.
- AI provider integration.
- Context management.
- Consent-based memory.
- Knowledge retrieval.
- Emotional support logic.
- Safety evaluation.
- User and preference management.

## 4. Technology stack

### Android
- Kotlin
- Android SDK
- Rive for character animation
- Android Intents
- Android Accessibility Services where appropriate
- Android speech and audio APIs

### Backend
- Python
- FastAPI
- Pydantic
- SQLAlchemy
- Pytest
- HTTPX

### Database and knowledge
- PostgreSQL
- Research document repository
- Embeddings and vector retrieval when justified

### Infrastructure
- Git and GitHub
- GitHub Actions
- HTTPS hosting
- Hosted AI model provider

Exact dependency versions must be verified against the
development environment before installation.

## 5. Interaction model

Voice is the primary interaction method.

Text remains available for:
- Silent environments.
- Accessibility.
- Long instructions.
- Reviewing previous responses.
- Situations where speech recognition is unsuitable.

Expected interaction lifecycle:

1. User activates Zoi.
2. Zoi enters listening state.
3. Speech is converted into text.
4. The request is interpreted.
5. A response or action is selected.
6. The appropriate safety and permission checks run.
7. Zoi responds or executes the action.
8. Zoi provides feedback.
9. Conversation context is retained according to user settings.

Continuous background listening is not assumed.
Android background microphone restrictions must be respected.

## 6. Intelligence router

Zoi must distinguish between different request categories.

- General conversation
- Factual questions
- Learning and explanations
- Device control
- App launching
- Reminders and organisation
- Information retrieval
- Personal memory
- Emotional support
- Crisis-related conversations

Simple questions should receive direct answers.
Complex questions should receive appropriately detailed answers.

The router may use an AI model for intent understanding,
but action execution must be controlled by deterministic
application logic.

## 7. Skills architecture

Each skill is an independent capability with a defined contract.

Initial planned skills:

- conversation
- device_control
- app_launcher
- reminders
- calendar
- weather
- web_search
- knowledge
- personal_memory
- emotional_support

Every skill must define:
- Name and description.
- Input schema.
- Validation rules.
- Required permissions.
- Execution function.
- Error handling.
- Tests.

Skills must not receive unrestricted device or backend access.

## 8. Android action security

The Android action engine executes approved device operations.

Examples:
- Open an installed application.
- Open supported system settings.
- Control the flashlight with permission.
- Create reminders through supported APIs.
- Perform supported interface actions through
  explicitly enabled Accessibility Services.

Sensitive or destructive actions require confirmation.

The backend cannot directly execute arbitrary Android commands.

The Android application validates every requested action
against its local capability and permission rules.

## 9. Conversation intelligence

Conversation processing follows this general sequence:

1. Authenticate the request.
2. Load permitted user context.
3. Classify the request.
4. Check safety requirements.
5. Retrieve knowledge when appropriate.
6. Generate a response or action proposal.
7. Validate the response or action.
8. Execute an approved action when applicable.
9. Return the result.

AI-generated action requests must never be executed blindly.

## 10. Memory

Memory is optional and consent-based.

Potential memory categories:
- User-approved preferences.
- Personal routines.
- Ongoing tasks.
- Conversation context.

Users must be able to inspect, correct and delete remembered
information.

Sensitive information must not be retained merely because
it appeared in a conversation.

## 11. Personality and communication

Zoi should be:
- Natural.
- Helpful.
- Direct.
- Patient.
- Respectful.
- Occasionally humorous.
- Context-aware.

Zoi should avoid:
- Unnecessary motivational speeches.
- Overcomplicating ordinary questions.
- Pretending to have human feelings.
- Manipulative or dependency-building behaviour.
- False claims about completed actions.

## 12. Emotional support and safety

Emotional support is one capability within Zoi.

Zoi must not:
- Diagnose mental-health conditions.
- Prescribe medication.
- Claim to replace professional care.
- Encourage users to abandon treatment.
- Reinforce harmful delusions.
- Treat an immediate crisis as ordinary conversation.

Crisis handling must prioritise appropriate human assistance
and professionally reviewed response procedures.

Qualified professionals must review mental-health content
and safety procedures before public deployment.

## 13. Initial database entities

- users
- user_preferences
- consent_records
- conversations
- messages
- memory_items
- reminders
- skill_permissions
- safety_events
- research_documents

Database access must enforce user-level isolation.

## 14. Privacy and security

- Collect the minimum necessary information.
- Protect API credentials and tokens.
- Use HTTPS for remote communication.
- Apply authentication and authorisation.
- Avoid exposing private conversation content in logs.
- Define data retention rules.
- Provide user-controlled deletion.
- Request Android permissions only when needed.
- Never use private conversations for model training
  without separate, explicit consent.

## 15. Development constraints

Development computer:
- macOS Mojave 10.14.6
- Intel x86_64
- Python 3.9.13
- Git 2.20.1
- Node.js 18.20.8
- No JDK currently installed

Testing device:
- Infinix Hot 50i
- Android 14
- 4 GB RAM

Development strategy:
- VS Code as the primary editor.
- Python backend developed locally.
- Android application compiled remotely initially.
- Physical-device testing instead of a local emulator.
- Lightweight architecture suitable for available hardware.

## 16. Development milestones

1. Product architecture and environment.
2. Robotic character prototype.
3. Android application foundation.
4. Floating companion interaction.
5. Python backend foundation.
6. Voice interaction.
7. Conversation intelligence.
8. Skills and device actions.
9. Research and knowledge retrieval.
10. Consent-based memory.
11. Emotional support and safety evaluation.
12. Integration and controlled beta.

## 17. Engineering principles

- Build a modular monolith first.
- Avoid unnecessary infrastructure.
- Keep Android device actions permission-controlled.
- Keep AI inference behind a provider interface.
- Test application logic independently of AI providers.
- Verify actions before reporting success.
- Document architectural changes.
- Treat safety and privacy as core engineering requirements.
EOF

git add docs/ARCHITECTURE.md
git commit -m "Redefine Zoi as a voice-first personal AI companion"
