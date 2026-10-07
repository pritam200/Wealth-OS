import { Fragment, useCallback, useEffect, useState } from 'react';
import { ShieldAlert, Lock, RefreshCw } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { adminApi, adminError, setAdminEnvironment } from '../api/admin';
import type { Me, TableInfo } from '../api/admin';

type Section =
  | 'dashboard' | 'emails' | 'ips' | 'data' | 'changes' | 'requests' | 'audit'
  | 'config' | 'flags' | 'secrets' | 'deployment' | 'sessions' | 'lockdown';

const NAV: { group: string; items: [Section, string][] }[] = [
  { group: 'Overview', items: [['dashboard', 'Dashboard']] },
  { group: 'Access Control', items: [['emails', 'Admin users & email allowlist'], ['ips', 'IP allowlist']] },
  { group: 'Production Data', items: [['data', 'Tables & search'], ['changes', 'Data changes']] },
  { group: 'Monitoring', items: [['requests', 'Request logs'], ['audit', 'Audit logs']] },
  { group: 'Configuration', items: [['config', 'Application & integrations'], ['flags', 'Feature flags'], ['secrets', 'Secrets']] },
  { group: 'Deployment', items: [['deployment', 'Version & environment']] },
  { group: 'Security', items: [['sessions', 'Active sessions'], ['lockdown', 'Emergency lockdown & MFA']] },
];

const ENV_STYLE: Record<string, string> = {
  PRODUCTION: 'bg-red-600 text-white', STAGE: 'bg-amber-500 text-black', DEV: 'bg-emerald-700 text-white',
};

/** Runs an action; if the server asks for a fresh authenticator code, collects it and retries once. */
type Guard = <T>(fn: () => Promise<T>) => Promise<T | undefined>;

function useAction(setError: (m: string | null) => void, askMfa: (retry: () => Promise<any>) => void) {
  return useCallback<Guard>(async fn => {
    setError(null);
    try { return await fn(); }
    catch (e: any) {
      if (adminError(e) === 'MFA_REQUIRED') { askMfa(fn); return undefined; }
      setError(adminError(e)); return undefined;
    }
  }, [setError, askMfa]);
}

const th = 'text-left text-[11px] uppercase tracking-wide text-gray-500 font-semibold py-1.5 pr-3';
const td = 'py-1.5 pr-3 text-xs text-ink align-top';
const Card = ({ title, children }: { title?: string; children: React.ReactNode }) => (
  <div className="card p-4 space-y-3">{title && <h3 className="text-sm font-semibold text-ink">{title}</h3>}{children}</div>
);
const Err = ({ msg }: { msg: string | null }) => msg ? <p className="text-xs text-bear">{msg}</p> : null;
const when = (s?: string) => s ? new Date(s).toLocaleString() : '—';

export function AdminConsolePage() {
  const [me, setMe] = useState<Me | null>(null);
  const [denied, setDenied] = useState(false);
  const [section, setSection] = useState<Section>('dashboard');
  const [error, setError] = useState<string | null>(null);
  const [mfaRetry, setMfaRetry] = useState<{ fn: () => Promise<any> } | null>(null);

  useEffect(() => {
    adminApi.me().then(m => { setMe(m); setAdminEnvironment(m.environment); }).catch(() => setDenied(true));
    return () => setAdminEnvironment(null);
  }, []);

  const guard = useAction(setError, fn => setMfaRetry({ fn }));
  const canEdit = me?.role === 'DEVELOPER' || me?.role === 'ADMIN';
  const isAdmin = me?.role === 'ADMIN';

  if (denied) return (
    <div className="max-w-md mx-auto mt-24 card p-6 text-center space-y-2">
      <ShieldAlert className="mx-auto text-bear" />
      <p className="text-sm text-ink font-semibold">Access denied</p>
      <p className="text-xs text-gray-500">The admin console is available only to approved administrators on approved networks.</p>
      <a href="/admin/login" className="text-xs text-brand-light underline" onClick={() => useAuthStore.getState().logout()}>Sign in with a different account</a>
    </div>
  );
  if (!me) return <p className="p-8 text-xs text-gray-500">Checking access…</p>;

  const props = { me, guard, canEdit, isAdmin, setError };
  return (
    <div className="min-h-screen bg-surface text-ink">
      <div className={`px-4 py-1.5 text-xs font-bold tracking-wide ${ENV_STYLE[me.environment]}`}>
        {me.environment}{me.environment === 'PRODUCTION' ? ' — you are working on live data. Every change is audited.' : ' environment'}
        <span className="float-right font-normal">{me.email} · {me.role} · {me.clientIp}</span>
      </div>
      <div className="flex">
        <nav className="w-60 shrink-0 border-r border-surface-border p-3 space-y-4 min-h-[calc(100vh-28px)]">
          <a href="/" className="text-xs text-brand-light">← Back to MarketAI</a>
          {NAV.map(g => (
            <div key={g.group}>
              <p className="text-[10px] uppercase tracking-wider text-gray-500 mb-1">{g.group}</p>
              {g.items.map(([id, label]) => (
                <button key={id} onClick={() => { setSection(id); setError(null); }}
                  className={`block w-full text-left text-xs px-2 py-1.5 rounded ${section === id ? 'bg-brand/15 text-brand-light font-semibold' : 'text-gray-400 hover:text-ink'}`}>{label}</button>
              ))}
            </div>
          ))}
        </nav>
        <main className="flex-1 p-5 space-y-3 min-w-0">
          <Err msg={error} />
          {section === 'dashboard' && <Dashboard />}
          {section === 'emails' && <Emails {...props} />}
          {section === 'ips' && <Ips {...props} />}
          {section === 'data' && <DataBrowser {...props} />}
          {section === 'changes' && <Changes {...props} />}
          {section === 'requests' && <RequestLogs />}
          {section === 'audit' && <Audit />}
          {(section === 'config' || section === 'flags' || section === 'secrets') && <Config {...props} view={section} />}
          {section === 'deployment' && <Deployment />}
          {section === 'sessions' && <Sessions {...props} />}
          {section === 'lockdown' && <Security {...props} />}
        </main>
      </div>
      {mfaRetry && <MfaModal onClose={() => setMfaRetry(null)} onDone={async () => {
        const fn = mfaRetry.fn; setMfaRetry(null);
        try { await fn(); } catch (e) { setError(adminError(e)); }
      }} />}
    </div>
  );
}

interface P { me: Me; guard: Guard; canEdit: boolean; isAdmin: boolean; setError: (m: string | null) => void }

/** A tiny data hook: load on mount and on demand. */
function useLoad<T>(fn: () => Promise<T>, deps: any[] = []) {
  const [data, setData] = useState<T | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const reload = useCallback(() => { fn().then(setData).catch(e => setErr(adminError(e))); }, deps); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { reload(); }, [reload]);
  return { data, err, reload };
}

function Dashboard() {
  const { data, err } = useLoad(adminApi.dashboard);
  if (err) return <Err msg={err} />;
  if (!data) return <p className="text-xs text-gray-500">Loading…</p>;
  const tiles: [string, any][] = [['Admin requests (24h)', data.requests24h], ['Denied (24h)', data.denied24h], ['IP allowlist entries', data.ipEntries],
    ['Email allowlist entries', data.adminEntries], ['Lockdown', data.lockdown ? 'ENGAGED' : 'Off'], ['MFA required', data.mfaRequired ? 'Yes' : 'No']];
  return <div className="grid grid-cols-2 md:grid-cols-3 gap-3">{tiles.map(([k, v]) => (
    <div key={k} className="card p-4"><p className="text-[11px] text-gray-500">{k}</p><p className="text-xl font-bold">{String(v)}</p></div>))}</div>;
}

function Emails({ guard, isAdmin }: P) {
  const { data, reload } = useLoad(adminApi.emails);
  const [value, setValue] = useState(''); const [kind, setKind] = useState('EMAIL'); const [role, setRole] = useState('READ_ONLY'); const [reason, setReason] = useState('');
  return (<>
    {isAdmin && <Card title="Add an administrator or a domain">
      <div className="flex flex-wrap gap-2">
        <select className="input" value={kind} onChange={e => setKind(e.target.value)}><option>EMAIL</option><option>DOMAIN</option></select>
        <input className="input" placeholder={kind === 'EMAIL' ? 'name@company.com' : 'company.com'} value={value} onChange={e => setValue(e.target.value)} />
        <select className="input" value={role} onChange={e => setRole(e.target.value)}><option>READ_ONLY</option><option>DEVELOPER</option><option>ADMIN</option></select>
        <input className="input" placeholder="Reason" value={reason} onChange={e => setReason(e.target.value)} />
        <button className="btn-primary text-xs px-3" onClick={async () => { await guard(() => adminApi.addEmail({ kind, value, role, reason })); setValue(''); reload(); }}>Add</button>
      </div>
      <p className="text-[11px] text-gray-500">A domain can never be given ADMIN. Console administrators set on the server (ADMIN_EMAILS) are not listed here.</p>
    </Card>}
    <Card title="Email allowlist"><table className="w-full"><thead><tr><th className={th}>Who</th><th className={th}>Type</th><th className={th}>Role</th><th className={th}>Status</th><th /></tr></thead>
      <tbody>{data?.map(e => <tr key={e.id}><td className={td}>{e.emailOrDomain}</td><td className={td}>{e.kind}</td><td className={td}>{e.role}</td><td className={td}>{e.status}</td>
        <td className={td}>{isAdmin && <button className="text-bear text-xs" onClick={async () => {
          const r = window.prompt('Reason for removing this entry?'); if (!r) return;
          await guard(() => adminApi.deleteEmail(e.id, r)); reload(); }}>Remove</button>}</td></tr>)}</tbody></table></Card>
  </>);
}

function Ips({ guard, isAdmin, me }: P) {
  const { data, reload } = useLoad(adminApi.ips);
  const [cidr, setCidr] = useState(''); const [description, setDescription] = useState(''); const [reason, setReason] = useState('');
  return (<>
    <p className="text-xs text-gray-500">You are connecting from <b>{me.clientIp}</b>. A change that would block this address is refused.</p>
    {isAdmin && <Card title="Allow an address">
      <div className="flex flex-wrap gap-2">
        <input className="input" placeholder="203.0.113.10 or 203.0.113.0/24" value={cidr} onChange={e => setCidr(e.target.value)} />
        <input className="input" placeholder="Description" value={description} onChange={e => setDescription(e.target.value)} />
        <input className="input" placeholder="Reason" value={reason} onChange={e => setReason(e.target.value)} />
        <button className="btn-primary text-xs px-3" onClick={async () => { await guard(() => adminApi.addIp({ cidr, description, reason })); setCidr(''); reload(); }}>Add</button>
      </div></Card>}
    <Card title={`IP allowlist (${me.environment})`}><table className="w-full"><thead><tr><th className={th}>Address</th><th className={th}>Description</th><th className={th}>Status</th><th className={th}>Added by</th><th /></tr></thead>
      <tbody>{data?.map(e => <tr key={e.id}><td className={td}>{e.cidr}</td><td className={td}>{e.description}</td><td className={td}>{e.status}</td><td className={td}>{e.createdBy}</td>
        <td className={td}>{isAdmin && <button className="text-bear text-xs" onClick={async () => {
          const r = window.prompt('Reason for removing this address?'); if (!r) return;
          await guard(() => adminApi.deleteIp(e.id, r)); reload(); }}>Remove</button>}</td></tr>)}</tbody></table></Card>
  </>);
}

function DataBrowser({ guard, canEdit }: P) {
  const { data: tables } = useLoad(adminApi.tables);
  const [table, setTable] = useState<TableInfo | null>(null);
  const [q, setQ] = useState(''); const [page, setPage] = useState(0);
  const [result, setResult] = useState<{ rows: Record<string, any>[]; total: number } | null>(null);
  const [editing, setEditing] = useState<Record<string, any> | null>(null);
  const [draft, setDraft] = useState<Record<string, any>>({}); const [reason, setReason] = useState('');
  const [preview, setPreview] = useState<any>(null); const [msg, setMsg] = useState<string | null>(null);

  const run = useCallback(() => { if (table) adminApi.search(table.table, q, page).then(setResult).catch(e => setMsg(adminError(e))); }, [table, q, page]);
  useEffect(() => { run(); }, [run]);

  const changed = () => Object.fromEntries(Object.entries(draft).filter(([k, v]) => v !== editing?.[k]));
  return (<>
    <Card title="Tables"><div className="flex flex-wrap gap-2">{tables?.map(t => (
      <button key={t.table} className={`text-xs px-3 py-1.5 rounded border ${table?.table === t.table ? 'border-brand text-brand-light' : 'border-surface-border text-gray-400'}`}
        onClick={() => { setTable(t); setPage(0); setEditing(null); setResult(null); }}>{t.label}</button>))}</div>
      <p className="text-[11px] text-gray-500">Only approved tables and columns are available. Secrets, tokens and documents are never exposed.</p></Card>
    {table && <Card title={table.label}>
      <input className="input w-72" placeholder="Search…" value={q} onChange={e => { setQ(e.target.value); setPage(0); }} />
      {msg && <Err msg={msg} />}
      <div className="overflow-x-auto"><table className="w-full"><thead><tr>{table.columns.map(c => <th key={c.name} className={th}>{c.name}</th>)}<th /></tr></thead>
        <tbody>{result?.rows.map(r => <tr key={String(r[table.idColumn])}>{table.columns.map(c => <td key={c.name} className={td}>{String(r[c.name] ?? '')}</td>)}
          <td className={td}>{canEdit && <button className="text-brand-light text-xs" onClick={() => { setEditing(r); setDraft({ ...r }); setPreview(null); setReason(''); }}>Edit</button>}</td></tr>)}</tbody></table></div>
      <div className="flex gap-3 text-xs text-gray-500 items-center">
        <button disabled={page === 0} onClick={() => setPage(page - 1)}>Prev</button><span>{result?.total ?? 0} rows · page {page + 1}</span>
        <button disabled={!result || (page + 1) * 25 >= result.total} onClick={() => setPage(page + 1)}>Next</button></div>
    </Card>}
    {table && editing && <Card title={`Edit ${table.label} #${editing[table.idColumn]}`}>
      {table.columns.filter(c => c.editable).map(c => (
        <label key={c.name} className="block text-xs">{c.name}{c.sensitive && <span className="text-amber-500"> · needs a second administrator</span>}
          {c.type === 'BOOLEAN'
            ? <input type="checkbox" className="ml-2" checked={!!draft[c.name]} onChange={e => setDraft({ ...draft, [c.name]: e.target.checked })} />
            : <input className="input w-full" value={draft[c.name] ?? ''} onChange={e => setDraft({ ...draft, [c.name]: e.target.value })} />}</label>))}
      <input className="input w-full" placeholder="Reason (required, recorded in the audit log)" value={reason} onChange={e => setReason(e.target.value)} />
      <div className="flex gap-2">
        <button className="btn-ghost text-xs px-3" onClick={async () => setPreview(await guard(() => adminApi.preview(table.table, String(editing[table.idColumn]), changed())))}>Preview</button>
        <button className="btn-primary text-xs px-3" disabled={!preview || !reason.trim()} onClick={async () => {
          const r = await guard(() => adminApi.update(table.table, String(editing[table.idColumn]), changed(), reason));
          if (r) { setMsg(r.pendingApproval ? `Sent for approval (request ${r.pendingApproval}).` : 'Saved.'); setEditing(null); run(); } }}>Confirm change</button>
        <button className="btn-ghost text-xs px-3" onClick={() => setEditing(null)}>Cancel</button></div>
      {preview && <pre className="text-[11px] bg-black/20 p-2 rounded overflow-x-auto">{JSON.stringify({ before: preview.before, after: preview.after, needsApproval: preview.needsApproval }, null, 2)}</pre>}
      {msg && <p className="text-xs text-brand-light">{msg}</p>}
    </Card>}
  </>);
}

function Changes({ guard, isAdmin, me }: P) {
  const { data, reload } = useLoad(adminApi.changes);
  return <Card title="Changes to sensitive fields"><table className="w-full"><thead><tr><th className={th}>#</th><th className={th}>Record</th><th className={th}>Change</th><th className={th}>By</th><th className={th}>Status</th><th /></tr></thead>
    <tbody>{data?.map(c => <tr key={c.id}><td className={td}>{c.id}</td><td className={td}>{c.tableName} #{c.recordId}</td><td className={td}><code>{c.changesJson}</code><br />{c.reason}</td>
      <td className={td}>{c.requestedBy}</td><td className={td}>{c.status}{c.decidedBy ? ` by ${c.decidedBy}` : ''}</td>
      <td className={td}>{isAdmin && c.status === 'PENDING' && c.requestedBy !== me.email && <>
        <button className="text-brand-light text-xs mr-2" onClick={async () => { await guard(() => adminApi.decide(c.id, true, '')); reload(); }}>Approve</button>
        <button className="text-bear text-xs" onClick={async () => { await guard(() => adminApi.decide(c.id, false, '')); reload(); }}>Reject</button></>}</td></tr>)}</tbody></table></Card>;
}

function RequestLogs() {
  const [f, setF] = useState({ ip: '', email: '', path: '', decision: '' });
  const { data, reload } = useLoad(() => adminApi.requestLogs(Object.fromEntries(Object.entries(f).filter(([, v]) => v))), [f]);
  return (<Card title="Admin request logs">
    <div className="flex flex-wrap gap-2">{(['ip', 'email', 'path'] as const).map(k => <input key={k} className="input" placeholder={k} value={f[k]} onChange={e => setF({ ...f, [k]: e.target.value })} />)}
      <select className="input" value={f.decision} onChange={e => setF({ ...f, decision: e.target.value })}><option value="">any decision</option><option>ALLOW</option><option>DENY</option></select>
      <button onClick={reload}><RefreshCw size={14} /></button></div>
    <table className="w-full"><thead><tr><th className={th}>Time</th><th className={th}>IP</th><th className={th}>User</th><th className={th}>Request</th><th className={th}>Status</th><th className={th}>Decision</th></tr></thead>
      <tbody>{data?.content.map(r => <tr key={r.id}><td className={td}>{when(r.occurredAt)}</td><td className={td}>{r.sourceIp}</td><td className={td}>{r.actorEmail}</td>
        <td className={td}>{r.method} {r.path}</td><td className={td}>{r.status} · {r.durationMs}ms</td><td className={td}>{r.decision}{r.denyReason ? ` (${r.denyReason})` : ''}</td></tr>)}</tbody></table></Card>);
}

function Audit() {
  const [page, setPage] = useState(0);
  const { data } = useLoad(() => adminApi.auditLogs(page), [page]);
  const [check, setCheck] = useState<string | null>(null);
  return (<Card title="Audit log (append-only, hash-chained)">
    <button className="btn-ghost text-xs px-3" onClick={async () => { const v = await adminApi.verifyAudit(); setCheck(`${v.valid ? 'Intact' : 'TAMPERED'} — ${v.message} (${v.checked} events checked)`); }}>Verify integrity</button>
    {check && <p className="text-xs text-brand-light">{check}</p>}
    <table className="w-full"><thead><tr><th className={th}>Time</th><th className={th}>Who</th><th className={th}>Action</th><th className={th}>Target</th><th className={th}>Reason</th><th className={th}>Before → After</th></tr></thead>
      <tbody>{data?.content.map(a => <tr key={a.id}><td className={td}>{when(a.occurredAt)}</td><td className={td}>{a.actorEmail}<br /><span className="text-gray-500">{a.actorIp}</span></td><td className={td}>{a.action}</td>
        <td className={td}>{a.targetType} {a.targetId}</td><td className={td}>{a.reason}</td><td className={td}><code className="break-all">{a.beforeValue} → {a.afterValue}</code></td></tr>)}</tbody></table>
    <div className="flex gap-3 text-xs text-gray-500"><button disabled={page === 0} onClick={() => setPage(page - 1)}>Newer</button><button disabled={!data || data.number + 1 >= Math.ceil(data.totalElements / data.size)} onClick={() => setPage(page + 1)}>Older</button></div>
  </Card>);
}

function Config({ guard, canEdit, view }: P & { view: 'config' | 'flags' | 'secrets' }) {
  const { data, reload } = useLoad(adminApi.config);
  if (!data) return null;
  const rows = data.items.filter(i => view === 'secrets' ? i.secret : view === 'config' ? !i.secret : false);
  if (view === 'flags') return <Card title="Feature flags">{data.flags.map(f => (
    <label key={f.name} className="flex items-center gap-2 text-xs"><input type="checkbox" checked={f.enabled} disabled={!canEdit} onChange={async e => {
      const reason = window.prompt(`Reason for turning ${f.name} ${e.target.checked ? 'on' : 'off'}?`); if (!reason) return;
      await guard(() => adminApi.setFlag(f.name, e.target.checked, reason)); reload(); }} />{f.name}</label>))}</Card>;
  return (<Card title={view === 'secrets' ? 'Secrets (write-only)' : 'Application & integrations'}>
    {view === 'secrets' && <p className="text-[11px] text-gray-500">Secret values are never shown. Rotation sends the new value straight to your secret manager and the application must be restarted to use it.{!data.rotationAvailable && ' No secret manager is connected on this server, so rotation is unavailable.'}</p>}
    <table className="w-full"><thead><tr><th className={th}>Name</th><th className={th}>{view === 'secrets' ? 'Status' : 'Value'}</th>{view === 'secrets' && <th className={th}>Last rotated</th>}<th /></tr></thead>
      <tbody>{rows.map(i => <tr key={i.name}><td className={td}>{i.name}<br /><span className="text-gray-500">{i.description}</span></td><td className={td}>{view === 'secrets' ? (i.configured ? `Configured · ${i.display}` : 'Not configured') : i.display}</td>
        {view === 'secrets' && <td className={td}>{when(i.lastRotatedAt)}{i.rotatedBy ? ` by ${i.rotatedBy}` : ''}</td>}
        <td className={td}>{view === 'secrets' && canEdit && data.rotationAvailable && <button className="text-brand-light text-xs" onClick={async () => {
          const value = window.prompt(`New value for ${i.name} (it will not be shown again):`); if (!value) return;
          const reason = window.prompt('Reason for rotating?'); if (!reason) return;
          await guard(() => adminApi.rotate(i.name, value, reason)); reload(); }}>Rotate</button>}</td></tr>)}</tbody></table></Card>);
}

function Deployment() {
  const { data } = useLoad(adminApi.deployment);
  return <Card title="Deployment">{data && <dl className="grid grid-cols-[160px_1fr] gap-y-1 text-xs">{Object.entries(data).map(([k, v]) => (
    <Fragment key={k}><dt className="text-gray-500">{k}</dt><dd>{Array.isArray(v) ? v.join(', ') : String(v ?? '—')}</dd></Fragment>))}</dl>}</Card>;
}

function Sessions({ guard, isAdmin }: P) {
  const { data, reload } = useLoad(adminApi.sessions);
  return <Card title="Active sessions (refresh tokens)"><table className="w-full"><thead><tr><th className={th}>Account</th><th className={th}>Sessions</th><th /></tr></thead>
    <tbody>{data?.map(s => <tr key={s.email}><td className={td}>{s.email}</td><td className={td}>{s.activeSessions}</td>
      <td className={td}>{isAdmin && <button className="text-bear text-xs" onClick={async () => {
        const r = window.prompt(`Disable ${s.email} and end all their sessions? Reason:`); if (!r) return;
        await guard(() => adminApi.disableAccount(s.email, r)); reload(); }}>Revoke</button>}</td></tr>)}</tbody></table></Card>;
}

function Security({ guard, isAdmin }: P) {
  const { data, reload } = useLoad(adminApi.lockdown);
  const mfa = useLoad(adminApi.mfa);
  const [enrol, setEnrol] = useState<{ secret: string; otpauthUri: string } | null>(null); const [code, setCode] = useState('');
  return (<>
    <Card title="Emergency lockdown">
      <p className="text-xs">{data?.engaged ? <b className="text-bear">ENGAGED by {data.engagedBy}: {data.reason}</b> : 'Not engaged.'}</p>
      <p className="text-[11px] text-gray-500">Lockdown blocks every admin request. Only a bootstrap administrator on a bootstrap address can release it.</p>
      {isAdmin && !data?.engaged && <button className="text-bear text-xs border border-bear/40 rounded px-3 py-1.5" onClick={async () => {
        const r = window.prompt('Reason for emergency lockdown?'); if (!r || !window.confirm('Block ALL admin access now?')) return;
        await guard(() => adminApi.engage(r)); reload(); }}><Lock size={12} className="inline mr-1" />Engage lockdown</button>}
      {isAdmin && data?.engaged && <button className="btn-primary text-xs px-3" onClick={async () => {
        const r = window.prompt('Reason for releasing?'); if (!r) return; await guard(() => adminApi.release(r)); reload(); }}>Release lockdown</button>}
    </Card>
    <Card title="Authenticator (MFA)">
      <p className="text-xs">{mfa.data?.enrolled ? 'Authenticator is set up.' : 'No authenticator yet.'} {mfa.data?.required ? 'Required for edits, rotations and approvals.' : 'Not required in this environment.'}</p>
      {!mfa.data?.enrolled && !enrol && <button className="btn-primary text-xs px-3" onClick={async () => setEnrol(await guard(() => adminApi.mfaEnroll()) ?? null)}>Set up authenticator</button>}
      {enrol && <div className="space-y-2 text-xs"><p>Add this key to an authenticator app (Google Authenticator, 1Password, Authy). It is shown only once.</p>
        <code className="block break-all bg-black/20 p-2 rounded">{enrol.secret}</code><code className="block break-all text-[10px] text-gray-500">{enrol.otpauthUri}</code>
        <div className="flex gap-2"><input className="input" placeholder="6-digit code" value={code} onChange={e => setCode(e.target.value)} />
          <button className="btn-primary text-xs px-3" onClick={async () => { await guard(() => adminApi.mfaVerify(code)); setEnrol(null); setCode(''); mfa.reload(); }}>Confirm</button></div></div>}
    </Card>
  </>);
}

function MfaModal({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [code, setCode] = useState(''); const [err, setErr] = useState<string | null>(null);
  return (<div className="fixed inset-0 bg-black/60 flex items-center justify-center z-50"><div className="card p-5 w-80 space-y-3">
    <p className="text-sm font-semibold">Confirm with your authenticator</p>
    <p className="text-xs text-gray-500">Enter the current 6-digit code. If you have not set one up, close this and use Security → Authenticator.</p>
    <input autoFocus className="input w-full" inputMode="numeric" maxLength={6} value={code} onChange={e => setCode(e.target.value)} />
    <Err msg={err} />
    <div className="flex gap-2"><button className="btn-primary text-xs px-3" onClick={async () => {
      try { await adminApi.mfaVerify(code); onDone(); } catch (e) { setErr(adminError(e)); } }}>Verify and continue</button>
      <button className="btn-ghost text-xs px-3" onClick={onClose}>Cancel</button></div></div></div>);
}
