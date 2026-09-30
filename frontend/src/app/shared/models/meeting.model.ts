/** Mirrors the backend `MeetingResponse`. */
export interface Meeting {
  id: string;
  title: string;
  summary: string;
  objectives: string[];
  keyPoints: string[];
  decisions: string[];
  outcomes: string[];
  actionItems: string[];
  nextSteps: string[];
  pendingDiscussions: string[];
  rawTranscript: string;
  createdAt: string;
}

/** Payload sent to `POST /api/meetings` (backend `MeetingRequest`). */
export interface MeetingRequest {
  title?: string;
  transcript: string;
}

/** Spring Data `Page<T>` envelope returned by `GET /api/meetings`. */
export interface MeetingPage {
  content: Meeting[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}