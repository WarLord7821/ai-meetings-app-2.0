import { Component, ElementRef, ViewChild, AfterViewChecked, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { DatePipe } from '@angular/common';

import { ChatService } from '../../../core/services/chat.service';
import { ChatMessage } from '../../models/chat.model';

/**
 * RAG chatbot panel: ask natural-language questions about past meetings
 * ("what were the objectives of my meeting with Sarah on the 19th?") and get
 * an answer grounded in the user's own stored meeting summaries, with the
 * source meeting(s) cited underneath.
 */
@Component({
  selector: 'app-chat-panel',
  imports: [ReactiveFormsModule, DatePipe],
  template: `
    <section class="chat-panel">
      <div class="chat-panel-head">
        <div>
          <h2>Ask about your meetings</h2>
          <p class="muted">
            e.g. "What were the objectives of my meeting with Sarah on the 19th?"
          </p>
        </div>
      </div>

      <div class="chat-log" #chatLog>
        @if (messages().length === 0) {
          <div class="chat-empty">
            <span class="chat-empty-icon">💬</span>
            <p>Ask a question about any meeting you've summarized — I'll search your
              history and answer using what was actually discussed.</p>
          </div>
        } @else {
          @for (message of messages(); track $index) {
            <div class="chat-msg" [class.chat-msg-user]="message.role === 'user'">
              <div
                class="chat-bubble"
                [class.chat-bubble-user]="message.role === 'user'"
                [class.chat-bubble-error]="message.failed"
              >
                @if (message.pending) {
                  <span class="chat-typing">
                    <span></span><span></span><span></span>
                  </span>
                } @else {
                  <p class="chat-text">{{ message.text }}</p>
                  @if (message.sources && message.sources.length > 0) {
                    <div class="chat-sources">
                      <span class="chat-sources-label">From:</span>
                      @for (source of message.sources; track source.id) {
                        <span class="chat-source-chip">
                          {{ source.title }} &middot; {{ source.createdAt | date: 'MMM d' }}
                        </span>
                      }
                    </div>
                  }
                }
              </div>
            </div>
          }
        }
      </div>

      @if (error()) {
        <div class="alert chat-alert">{{ error() }}</div>
      }

      <form class="chat-input-row" [formGroup]="form" (ngSubmit)="onSubmit()">
        <input
          type="text"
          formControlName="question"
          placeholder="Ask a question about your meetings…"
          class="field chat-input"
          autocomplete="off"
        />
        <button type="submit" class="btn btn-primary" [disabled]="form.invalid || loading()">
          {{ loading() ? '…' : 'Ask' }}
        </button>
      </form>
    </section>
  `,
  styles: [`
    .chat-panel {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 12px;
      padding: 1.25rem;
      margin-bottom: 1.5rem;
      display: flex;
      flex-direction: column;
    }
    .chat-panel-head h2 { margin: 0 0 0.25rem; font-size: 1rem; }
    .chat-panel-head .muted { margin: 0; }

    .chat-log {
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
      max-height: 22rem;
      overflow-y: auto;
      margin: 1rem 0;
      padding-right: 0.25rem;
      scroll-behavior: smooth;
    }

    .chat-empty {
      display: flex;
      flex-direction: column;
      align-items: center;
      text-align: center;
      gap: 0.5rem;
      padding: 1.75rem 1rem;
      color: var(--muted);
    }
    .chat-empty-icon { font-size: 1.75rem; }
    .chat-empty p { margin: 0; font-size: 0.875rem; max-width: 28rem; }

    .chat-msg { display: flex; }
    .chat-msg-user { justify-content: flex-end; }

    .chat-bubble {
      max-width: 85%;
      background: var(--bg);
      border: 1px solid var(--border);
      border-radius: 12px;
      border-top-left-radius: 4px;
      padding: 0.6rem 0.85rem;
    }
    .chat-bubble-user {
      background: var(--indigo);
      color: #fff;
      border-color: var(--indigo);
      border-top-left-radius: 12px;
      border-top-right-radius: 4px;
    }
    .chat-bubble-error { background: var(--danger-bg); border-color: #fecaca; color: var(--danger); }

    .chat-text { margin: 0; font-size: 0.9rem; line-height: 1.45; white-space: pre-wrap; }

    .chat-sources {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.35rem;
      margin-top: 0.5rem;
      padding-top: 0.5rem;
      border-top: 1px solid rgb(0 0 0 / 0.08);
    }
    .chat-sources-label { font-size: 0.7rem; color: var(--muted); font-weight: 600; }
    .chat-source-chip {
      font-size: 0.7rem;
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 999px;
      padding: 0.1rem 0.55rem;
      color: var(--muted);
    }

    .chat-typing { display: inline-flex; gap: 0.25rem; padding: 0.15rem 0; }
    .chat-typing span {
      width: 0.4rem;
      height: 0.4rem;
      border-radius: 50%;
      background: var(--muted);
      animation: chat-typing-bounce 1.1s infinite ease-in-out;
    }
    .chat-typing span:nth-child(2) { animation-delay: 0.15s; }
    .chat-typing span:nth-child(3) { animation-delay: 0.3s; }
    @keyframes chat-typing-bounce {
      0%, 60%, 100% { transform: translateY(0); opacity: 0.5; }
      30% { transform: translateY(-0.2rem); opacity: 1; }
    }

    .chat-alert { margin-bottom: 0.75rem; }

    .chat-input-row { display: flex; gap: 0.5rem; }
    .chat-input { flex: 1; margin-bottom: 0; }
  `],
})
export class ChatPanelComponent implements AfterViewChecked {
  private readonly fb = inject(FormBuilder);
  private readonly chat = inject(ChatService);

  @ViewChild('chatLog') private chatLogRef?: ElementRef<HTMLDivElement>;
  private shouldScroll = false;

  protected readonly messages = signal<ChatMessage[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal('');

  protected readonly form = this.fb.nonNullable.group({
    question: ['', [Validators.required, Validators.maxLength(2000)]],
  });

  ngAfterViewChecked(): void {
    if (this.shouldScroll && this.chatLogRef) {
      const el = this.chatLogRef.nativeElement;
      el.scrollTop = el.scrollHeight;
      this.shouldScroll = false;
    }
  }

  onSubmit(): void {
    if (this.form.invalid || this.loading()) return;

    const question = this.form.value.question!.trim();
    if (!question) return;

    this.error.set('');
    this.loading.set(true);
    this.form.reset();

    this.messages.update((msgs) => [
      ...msgs,
      { role: 'user', text: question },
      { role: 'assistant', text: '', pending: true },
    ]);
    this.shouldScroll = true;

    this.chat.ask({ question }).subscribe({
      next: (result) => {
        this.replacePendingReply({
          role: 'assistant',
          text: result.answer,
          sources: result.sources,
        });
      },
      error: (err) => {
        const body = err?.error;
        const message = typeof body === 'string' ? body : (body?.error ?? 'Something went wrong. Please try again.');
        this.replacePendingReply({ role: 'assistant', text: message, failed: true });
      },
      complete: () => this.loading.set(false),
    });
  }

  private replacePendingReply(reply: ChatMessage): void {
    this.messages.update((msgs) => {
      const next = [...msgs];
      const lastIndex = next.length - 1;
      if (lastIndex >= 0 && next[lastIndex].pending) {
        next[lastIndex] = reply;
      } else {
        next.push(reply);
      }
      return next;
    });
    this.shouldScroll = true;
  }
}
