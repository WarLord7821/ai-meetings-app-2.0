/** A single meeting cited as a source for a chat answer. */
export interface ChatSource {
  id: string;
  title: string;
  createdAt: string;
}

/** Mirrors the backend `ChatResponse`. */
export interface ChatAnswer {
  answer: string;
  sources: ChatSource[];
}

/** Payload sent to `POST /api/chat` (backend `ChatRequest`). */
export interface ChatRequest {
  question: string;
}

/** One rendered turn in the chat panel (both user questions and assistant answers). */
export interface ChatMessage {
  role: 'user' | 'assistant';
  text: string;
  sources?: ChatSource[];
  /** True while an assistant reply is still being generated for this turn. */
  pending?: boolean;
  /** True if the assistant turn failed and `text` holds an error message. */
  failed?: boolean;
}
