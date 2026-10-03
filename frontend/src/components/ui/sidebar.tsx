// Adapted from shadcn/ui d75a96ab781f3d659be1ad287347d5887ce9f2fc.
// Copyright (c) 2023 shadcn. MIT: licenses/frontend/shadcn-ui-LICENSE.txt.
// Keep provider/sidebar/rail; use the existing layout and Button styles.
// All window sizes use full collapse; no mobile Sheet branch or file:// cookies.
import { createContext, useContext, useState, type ComponentProps } from 'react';
import { Slot } from '@radix-ui/react-slot';
import { Button } from './button';

type SidebarContextValue = { open: boolean; toggleSidebar: () => void };
const SidebarContext = createContext<SidebarContextValue | null>(null);
function useSidebar() {
  const context = useContext(SidebarContext);
  if(!context)throw new Error('Sidebar requires SidebarProvider');
  return context;
}
function SidebarProvider({ defaultOpen = true, asChild = false, ...props }:
  ComponentProps<'div'> & { defaultOpen?: boolean; asChild?: boolean }) {
  const [open, setOpen] = useState(defaultOpen);
  const Comp = asChild ? Slot : 'div';
  return <SidebarContext.Provider value={{ open, toggleSidebar: () => setOpen(value => !value) }}>
    <Comp data-slot="sidebar-wrapper" {...props}/>
  </SidebarContext.Provider>;
}
function Sidebar({ asChild = false, ...props }: ComponentProps<'aside'> & { asChild?: boolean }) {
  const { open } = useSidebar();
  if(!open)return null;
  const Comp = asChild ? Slot : 'aside';
  return <Comp data-slot="sidebar" data-state="expanded" data-collapsible="offcanvas" {...props}/>;
}
function SidebarRail({ onClick, ...props }: ComponentProps<typeof Button>) {
  const { open, toggleSidebar } = useSidebar();
  return <Button variant="unstyled" data-slot="sidebar-rail" data-sidebar="rail" className="sidebar-rail"
    data-state={open ? 'expanded' : 'collapsed'} title={open ? '收起侧栏' : '展开侧栏'}
    aria-label={open ? '收起侧栏' : '展开侧栏'} aria-expanded={open}
    onClick={event => { onClick?.(event); if(!event.defaultPrevented)toggleSidebar(); }} {...props}>
  </Button>;
}
export { SidebarProvider, Sidebar, SidebarRail, useSidebar };
