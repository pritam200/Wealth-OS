import { useState } from 'react';
import { ChevronLeft, ChevronRight, Zap } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';

export interface SubTab { id: number; label: string; }
export interface NavSection { id: string; label: string; Icon: LucideIcon; tabs: readonly SubTab[]; }

interface Props {
  sections: readonly NavSection[];
  activeTab: number;
  onSelect: (tabId: number) => void;
}

// Left rail navigation — a solid-fill gradient pill for the active section (not just a
// tint) is the main visual anchor that makes the current location unmistakable at a glance.
export function Sidebar({ sections, activeTab, onSelect }: Props) {
  const [collapsed, setCollapsed] = useState(false);
  const [openSection, setOpenSection] = useState<string | null>(
    sections.find(s => s.tabs.some(t => t.id === activeTab))?.id ?? null
  );

  return (
    <aside className={`flex flex-col h-screen shrink-0 bg-surface-card/95 backdrop-blur border-r border-surface-border transition-all duration-200 ease-snap ${collapsed ? 'w-16' : 'w-60'}`}>
      <div className={`flex items-center h-14 border-b border-surface-border/70 shrink-0 ${collapsed ? 'justify-center' : 'px-4 gap-2.5'}`}>
        <div className="w-8 h-8 bg-brand-gradient rounded-xl flex items-center justify-center shrink-0" style={{ boxShadow: '0 4px 16px rgba(109,94,252,0.45)' }}>
          <Zap size={16} className="text-white" fill="currentColor" />
        </div>
        {!collapsed && (
          <div className="leading-tight">
            <div className="text-sm font-extrabold text-white tracking-tight">MarketAI</div>
            <div className="text-2xs text-gray-600 -mt-0.5">Wealth OS</div>
          </div>
        )}
      </div>

      <nav className="flex-1 overflow-y-auto py-3 space-y-0.5">
        {sections.map(section => {
          const Icon = section.Icon;
          const isActiveSection = section.tabs.some(t => t.id === activeTab);
          const expanded = collapsed ? false : (openSection === section.id || isActiveSection);
          const hasSub = section.tabs.length > 1;

          return (
            <div key={section.id} className="px-2.5">
              <button
                onClick={() => {
                  if (hasSub && !collapsed) {
                    setOpenSection(o => (o === section.id ? null : section.id));
                  }
                  onSelect(section.tabs[0].id);
                }}
                title={collapsed ? section.label : undefined}
                className={`w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm transition-all duration-150 ${
                  isActiveSection
                    ? 'text-white font-semibold bg-brand-gradient shadow-glow'
                    : 'text-gray-400 hover:text-white hover:bg-surface-hover'
                } ${collapsed ? 'justify-center' : ''}`}
              >
                <Icon size={17} className="shrink-0" />
                {!collapsed && <span className="flex-1 text-left truncate">{section.label}</span>}
              </button>

              {expanded && hasSub && (
                <div className="mt-1 mb-1.5 ml-4 border-l-2 border-surface-border pl-3 space-y-0.5">
                  {section.tabs.map(t => (
                    <button
                      key={t.id}
                      onClick={() => onSelect(t.id)}
                      className={`w-full text-left px-2.5 py-1.5 rounded-lg text-xs transition-colors ${
                        activeTab === t.id ? 'text-brand-light bg-brand/10 font-semibold' : 'text-gray-500 hover:text-gray-200 hover:bg-surface-hover'
                      }`}
                    >
                      {t.label}
                    </button>
                  ))}
                </div>
              )}
            </div>
          );
        })}
      </nav>

      <button onClick={() => setCollapsed(c => !c)}
        className="flex items-center justify-center h-10 border-t border-surface-border/70 text-gray-500 hover:text-white hover:bg-surface-hover transition-colors shrink-0">
        {collapsed ? <ChevronRight size={14} /> : <ChevronLeft size={14} />}
      </button>
    </aside>
  );
}
