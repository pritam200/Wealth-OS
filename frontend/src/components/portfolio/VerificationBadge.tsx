import type { HoldingDto } from '../../types';

/** Whether an institution has confirmed this position (from the canonical ledger). Renders nothing when the ledger has no view. */
export function VerificationBadge({ h }: { h: HoldingDto }) {
  if (!h.verificationState) return null;
  const when = h.lastVerifiedAt ? new Date(h.lastVerifiedAt).toLocaleDateString('en-IN') : null;
  if (h.verificationState === 'VERIFIED')
    return <div className="text-2xs text-bull" title={`Confirmed by ${h.verificationSource ?? 'an institution'}${when ? ` on ${when}` : ''}`}>verified{when ? ` · ${when}` : ''}</div>;
  if (h.verificationState === 'NEEDS_RECONCILIATION')
    return <div className="text-2xs text-bear" title="The institution reports a different quantity — see Needs Review → Data Platform → Reconciliation">
      institution shows {h.institutionQuantity ?? '?'} units — review
    </div>;
  return <div className="text-2xs text-gray-600" title="Built from emails and manual entries; no institution has confirmed it">unverified</div>;
}
