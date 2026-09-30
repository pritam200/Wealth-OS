import { useState } from 'react';
import { Send, Loader2, MessageCircle, AlertTriangle } from 'lucide-react';
import { advisorApi, type AdvisorAskResponse, type AdvisorEvidence } from '../../api/advisor';
import { AiProvenanceButton } from '../shared/AiProvenanceButton';
import { MaskedSentence, useMaskedSentence, useMaskedText } from '../shared/Amount';

interface Message {
  question: string;
  response?: AdvisorAskResponse;
  error?: string;
}

const SUGGESTIONS = [
  'What is my net worth?',
  'Why did my net worth change this month?',
  'Which investments are still pending?',
  'Find duplicate transactions',
  'Find potentially missing transactions',
  'Show every source document behind my MF holdings',
];

const fmtINR = (n: number) => '₹' + n.toLocaleString('en-IN', { maximumFractionDigits: 2 });

function EvidenceList({ items }: { items: AdvisorEvidence[] }) {
  const maskText = useMaskedText();
  if (items.length === 0) return null;
  return (
    <details className="mt-2">
      <summary className="text-2xs text-gray-500 cursor-pointer">Records behind this answer ({items.length})</summary>
      <ul className="mt-1.5 space-y-1">
        {items.map((e, i) => (
          <li key={i} className="flex items-center gap-2 text-2xs">
            <span className="text-gray-500 font-mono shrink-0">{e.date ?? '—'}</span>
            <span className="text-gray-300 truncate"><MaskedSentence text={e.label} /></span>
            {e.source && <span className="text-gray-600 truncate">· {e.source}</span>}
            {e.amount != null && <span className="font-mono text-gray-400 ml-auto shrink-0">{maskText(fmtINR(e.amount))}</span>}
            {e.kind && e.id != null && <AiProvenanceButton record={{ kind: e.kind, id: e.id }} />}
          </li>
        ))}
      </ul>
    </details>
  );
}

// Chat layer over data the app already computes — every answer here comes back from a
// real query over the ledger, the data audit or the reconciliation records, never free-form
// text the model made up. The records behind an answer are listed, each opening its source.
export function AdvisorChat() {
  const [question, setQuestion] = useState('');
  const [messages, setMessages] = useState<Message[]>([]);
  const [loading, setLoading] = useState(false);
  const maskSentence = useMaskedSentence();

  const ask = async (q: string) => {
    const text = q.trim();
    if (!text || loading) return;
    setLoading(true);
    setQuestion('');
    setMessages(prev => [...prev, { question: text }]);
    try {
      const { data } = await advisorApi.ask(text);
      setMessages(prev => prev.map((m, i) => i === prev.length - 1 ? { ...m, response: data } : m));
    } catch {
      setMessages(prev => prev.map((m, i) => i === prev.length - 1
        ? { ...m, error: 'Could not reach the advisor — please try again.' } : m));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="card-elevated">
      <div className="flex items-center gap-2.5 mb-3">
        <div className="icon-badge-brand"><MessageCircle size={15} /></div>
        <h3 className="font-bold text-ink text-sm">Ask your data</h3>
      </div>

      {messages.length === 0 ? (
        <div className="flex flex-wrap gap-2 mb-3">
          {SUGGESTIONS.map(s => (
            <button key={s} onClick={() => ask(s)} className="btn-secondary text-2xs py-1 px-2.5">
              {s}
            </button>
          ))}
        </div>
      ) : (
        <div className="space-y-3 mb-3 max-h-80 overflow-y-auto">
          {messages.map((m, i) => (
            <div key={i} className="space-y-1.5">
              <p className="text-xs text-gray-400"><span className="text-ink font-medium">You:</span> {m.question}</p>
              {m.response ? (
                <div className="text-xs text-gray-300 bg-surface-hover rounded-lg p-2.5">
                  {!m.response.available ? (
                    <div className="flex items-start gap-1.5 text-neutral">
                      <AlertTriangle size={12} className="mt-0.5 shrink-0" /> {m.response.answer}
                    </div>
                  ) : (
                    <>
                      <p>{maskSentence(m.response.answer)}</p>
                      <EvidenceList items={m.response.evidence ?? []} />
                      {m.response.tool !== 'UNKNOWN' && (
                        <p className="text-2xs text-gray-600 mt-1.5">Grounded in: {m.response.tool.replace(/_/g, ' ').toLowerCase()}</p>
                      )}
                    </>
                  )}
                </div>
              ) : m.error ? (
                <p className="text-xs text-bear">{m.error}</p>
              ) : (
                <div className="text-xs text-gray-500 flex items-center gap-1.5"><Loader2 size={11} className="animate-spin" /> Thinking…</div>
              )}
            </div>
          ))}
        </div>
      )}

      <div className="flex items-center gap-2">
        <input
          value={question}
          onChange={e => setQuestion(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter') ask(question); }}
          placeholder="Ask about your net worth, spending, plan, holdings, duplicates or missing records…"
          className="input-field flex-1 text-xs"
          disabled={loading}
        />
        <button onClick={() => ask(question)} disabled={loading || !question.trim()} className="btn-primary p-2">
          {loading ? <Loader2 size={14} className="animate-spin" /> : <Send size={14} />}
        </button>
      </div>
      <p className="text-2xs text-gray-700 mt-2">Answers are grounded in your own recorded data — never free-form financial advice.</p>
    </div>
  );
}
