"use client";
import Link from "next/link";
import type { ReactNode } from "react";
import { AdminSidebar } from "@/components/admin";
import { Notice, pill } from "@/components/app";
import { SidebarInset, SidebarProvider } from "@/components/ui/sidebar";
import { TooltipProvider } from "@/components/ui/tooltip";
import { fixtureMode } from "@/lib/admin";
import { useSession } from "@/lib/session";

/** Admins only. The API checks `is_admin` on every admin route; this gate just keeps the UI out of sight. */
export default function AdminLayout({ children }: { children: ReactNode }) {
  const { s, ready } = useSession();
  if (!ready) return null;
  if (!s.user?.is_admin && !fixtureMode())
    return <main className="grid min-h-dvh place-items-center p-6"><Notice title="This page doesn't exist" body="Check the address, or head back home."><Link href="/" className={pill.primary}>Go home</Link></Notice></main>;
  return (
    <TooltipProvider>
      <SidebarProvider>
        <AdminSidebar />
        <SidebarInset className="min-w-0 bg-background">{children}</SidebarInset>
      </SidebarProvider>
    </TooltipProvider>
  );
}
