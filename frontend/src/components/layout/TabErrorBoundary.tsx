import { Component } from 'react';
import type { ErrorInfo, ReactNode } from 'react';
import { AlertTriangle, RotateCw } from 'lucide-react';

interface Props { children: ReactNode; tabLabel?: string }
interface State { error: Error | null }

/**
 * Keeps one broken tab from taking down the whole app.
 *
 * Two failures this actually catches: a render-time exception inside a tab (previously an
 * unmounted white screen with only a console message), and a failed dynamic import — which
 * happens routinely after a redeploy, when the browser holds an old index referencing chunk
 * filenames that no longer exist. A chunk-load failure is offered a reload, because reloading
 * genuinely fixes that one; a render error is not, because it would just fail again.
 */
export class TabErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Console only: this app shows real money, and an error payload can quote values off the
    // screen it failed on, so it must not be shipped anywhere off the machine.
    console.error('Tab render failed:', error, info.componentStack);
  }

  private isChunkLoadFailure(e: Error): boolean {
    const text = `${e.name} ${e.message}`;
    return /ChunkLoadError|Loading chunk|Failed to fetch dynamically imported module|error loading dynamically imported module/i.test(text);
  }

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;

    const stale = this.isChunkLoadFailure(error);
    return (
      <div className="card-elevated max-w-xl mx-auto mt-8 text-center">
        <div className="icon-badge icon-badge-bear mx-auto mb-3"><AlertTriangle size={15} /></div>
        <h3 className="font-bold text-ink text-sm mb-1">
          {stale ? 'This tab needs a refresh' : `Could not display ${this.props.tabLabel ?? 'this tab'}`}
        </h3>
        <p className="text-xs text-gray-500 leading-relaxed mb-4">
          {stale
            ? 'The app was updated while this page was open, so part of it could no longer be loaded. Reloading picks up the new version.'
            : 'Something went wrong rendering this section. Your data is unaffected — nothing was saved or changed. Other tabs still work.'}
        </p>
        {stale ? (
          <button className="btn-primary text-xs" onClick={() => window.location.reload()}>
            <RotateCw size={13} /> Reload
          </button>
        ) : (
          <button className="btn-secondary text-xs" onClick={() => this.setState({ error: null })}>
            <RotateCw size={13} /> Try again
          </button>
        )}
        <p className="text-2xs text-gray-700 mt-3 font-mono break-words">{error.message}</p>
      </div>
    );
  }
}
