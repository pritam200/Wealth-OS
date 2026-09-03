import { useState, useRef, useEffect } from 'react';
import { Send, Bot, User, Sparkles } from 'lucide-react';
import { aiApi } from '../../api/ai';

interface Message {
  role: 'user' | 'assistant';
  content: string;
  time: string;
}

const SUGGESTIONS = [
  'Why is Nifty falling today?',
  'Which sectors look strong this week?',
  'Explain HDFC Bank movement',
  'Is now a good time to buy IT stocks?',
];

export function AiCopilot() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const send = async (text: string) => {
    if (!text.trim() || loading) return;
    const userMsg: Message = { role: 'user', content: text, time: new Date().toLocaleTimeString() };
    setMessages(prev => [...prev, userMsg]);
    setInput('');
    setLoading(true);

    try {
      const { data } = await aiApi.chat(text);
      setMessages(prev => [...prev, {
        role: 'assistant',
        content: data.rawResponse || data.summary,
        time: new Date().toLocaleTimeString(),
      }]);
    } catch {
      setMessages(prev => [...prev, {
        role: 'assistant',
        content: 'Sorry, I could not process your request. Please check your Gemini API key.',
        time: new Date().toLocaleTimeString(),
      }]);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="card flex flex-col h-[600px]">
      <div className="flex items-center gap-2 mb-4 pb-4 border-b border-surface-border">
        <Sparkles size={18} className="text-brand" />
        <h2 className="font-semibold text-white">AI Market Copilot</h2>
        <span className="ml-auto text-xs text-gray-600">Powered by Gemini</span>
      </div>

      {/* Messages */}
      <div className="flex-1 overflow-y-auto space-y-4 pr-1">
        {messages.length === 0 && (
          <div className="text-center py-8">
            <Bot size={40} className="text-brand mx-auto mb-3 opacity-60" />
            <p className="text-gray-500 text-sm mb-4">Ask me anything about Indian markets</p>
            <div className="flex flex-wrap gap-2 justify-center">
              {SUGGESTIONS.map(s => (
                <button
                  key={s}
                  onClick={() => send(s)}
                  className="text-xs bg-surface-hover hover:bg-brand/20 border border-surface-border hover:border-brand/40 text-gray-400 hover:text-brand px-3 py-1.5 rounded-full transition-all"
                >
                  {s}
                </button>
              ))}
            </div>
          </div>
        )}

        {messages.map((m, i) => (
          <div key={i} className={`flex gap-3 ${m.role === 'user' ? 'flex-row-reverse' : ''}`}>
            <div className={`w-7 h-7 rounded-full flex items-center justify-center shrink-0 ${
              m.role === 'user' ? 'bg-brand' : 'bg-surface-hover border border-surface-border'
            }`}>
              {m.role === 'user' ? <User size={13} /> : <Bot size={13} className="text-brand" />}
            </div>
            <div className={`max-w-[80%] rounded-xl px-4 py-3 text-sm leading-relaxed ${
              m.role === 'user'
                ? 'bg-brand/20 text-gray-200 border border-brand/30'
                : 'bg-surface-hover text-gray-300 border border-surface-border'
            }`}>
              {m.content}
              <div className="text-xs text-gray-600 mt-1">{m.time}</div>
            </div>
          </div>
        ))}

        {loading && (
          <div className="flex gap-3">
            <div className="w-7 h-7 rounded-full bg-surface-hover border border-surface-border flex items-center justify-center">
              <Bot size={13} className="text-brand" />
            </div>
            <div className="bg-surface-hover border border-surface-border rounded-xl px-4 py-3">
              <div className="flex gap-1">
                {[0,1,2].map(i => (
                  <div key={i} className="w-1.5 h-1.5 bg-brand rounded-full animate-bounce"
                    style={{ animationDelay: `${i * 0.15}s` }} />
                ))}
              </div>
            </div>
          </div>
        )}

        <div ref={bottomRef} />
      </div>

      {/* Input */}
      <div className="mt-4 pt-4 border-t border-surface-border flex gap-2">
        <input
          value={input}
          onChange={e => setInput(e.target.value)}
          onKeyDown={e => e.key === 'Enter' && !e.shiftKey && send(input)}
          placeholder="Ask about markets, stocks, your portfolio..."
          className="flex-1 bg-surface-hover border border-surface-border rounded-lg px-4 py-2.5 text-sm text-gray-200 placeholder-gray-600 outline-none focus:border-brand/50 transition-colors"
        />
        <button
          onClick={() => send(input)}
          disabled={loading || !input.trim()}
          className="btn-primary p-2.5 disabled:opacity-40 disabled:cursor-not-allowed"
        >
          <Send size={16} />
        </button>
      </div>
    </div>
  );
}
