export interface AiJsonRequest { system: string; user: string }
export interface AiUsage { prompt_tokens?: number; completion_tokens?: number }
export interface AiJsonResponse { json: unknown; usage: AiUsage }
export interface RelationshipAiProvider {
  readonly available: boolean;
  readonly model: string;
  completeJson(request: AiJsonRequest): Promise<AiJsonResponse>;
}

interface DeepSeekOptions {
  apiKey?: string;
  baseUrl?: string;
  model?: string;
  timeoutMs?: number;
  fetcher?: (url: string, init: RequestInit) => Promise<Response>;
}

function extractJson(text: string): unknown {
  const cleaned = text.trim().replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '');
  return JSON.parse(cleaned);
}

export class DeepSeekProvider implements RelationshipAiProvider {
  private readonly apiKey: string;
  private readonly baseUrl: string;
  private readonly timeoutMs: number;
  private readonly fetcher: (url: string, init: RequestInit) => Promise<Response>;
  readonly model: string;

  constructor(options: DeepSeekOptions = {}) {
    this.apiKey = options.apiKey ?? process.env.DEEPSEEK_API_KEY ?? '';
    this.baseUrl = (options.baseUrl ?? process.env.DEEPSEEK_BASE_URL ?? 'https://api.deepseek.com').replace(/\/$/, '');
    this.model = options.model ?? process.env.DEEPSEEK_MODEL ?? 'deepseek-chat';
    this.timeoutMs = options.timeoutMs ?? Number(process.env.DEEPSEEK_TIMEOUT_MS ?? 12_000);
    this.fetcher = options.fetcher ?? ((url, init) => fetch(url, init));
  }

  get available(): boolean { return this.apiKey.trim().length > 0; }

  async completeJson(request: AiJsonRequest): Promise<AiJsonResponse> {
    if (!this.available) throw new Error('AI provider unavailable');
    const response = await this.fetcher(`${this.baseUrl}/chat/completions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${this.apiKey}` },
      body: JSON.stringify({
        model: this.model,
        response_format: { type: 'json_object' },
        temperature: 0.4,
        messages: [
          { role: 'system', content: request.system },
          { role: 'user', content: request.user },
        ],
      }),
      signal: AbortSignal.timeout(this.timeoutMs),
    });
    if (!response.ok) throw new Error(`AI provider request failed (${response.status})`);
    const body = await response.json() as {
      choices?: Array<{ message?: { content?: string } }>;
      usage?: AiUsage;
    };
    const content = body.choices?.[0]?.message?.content;
    if (!content) throw new Error('AI provider returned empty content');
    return { json: extractJson(content), usage: body.usage ?? {} };
  }
}
