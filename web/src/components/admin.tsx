"use client";
/* eslint-disable @typescript-eslint/no-explicit-any -- admin API rows are untyped JSON, shapes documented in server/API.md */
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { AnimatePresence } from "motion/react";
import { Area, Bar, CartesianGrid, ComposedChart, Line, LineChart, XAxis, YAxis } from "recharts";
import { toast } from "sonner";
import {
  Activity, AlertTriangle, ArrowLeft, Clapperboard, Flame, KeyRound, LayoutDashboard, LogOut, Radio, RefreshCw, ScrollText, Search,
  Server, ShieldCheck, ShieldOff, Smartphone, Trash2, Tv, UserCheck, UserPlus, UserX, Users, Monitor,
} from "lucide-react";
import { Avatar, Notice } from "@/components/app";
import { ReelMark } from "@/components/reel";
import { AnimatedListItem } from "@/components/ui/animated-list";
import { BorderBeam } from "@/components/ui/border-beam";
import { BlurFade } from "@/components/ui/blur-fade";
import { ChartContainer, ChartTooltip, ChartTooltipContent, type ChartConfig } from "@/components/ui/chart";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { NumberTicker } from "@/components/ui/number-ticker";
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription } from "@/components/ui/sheet";
import {
  Sidebar, SidebarContent, SidebarFooter, SidebarGroup, SidebarGroupLabel, SidebarHeader, SidebarMenu, SidebarMenuButton, SidebarMenuItem, SidebarTrigger,
} from "@/components/ui/sidebar";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { adminAct, adminCreate, adminGet, adminPin, useAdmin } from "@/lib/admin";
import { useSession } from "@/lib/session";
import { cn } from "@/lib/utils";

// ---- format ---------------------------------------------------------------
const rtf = new Intl.RelativeTimeFormat("en", { numeric: "auto" });
export function ago(t?: number | string | null) {
  if (!t) return "never";
  const s = (new Date(t).getTime() - Date.now()) / 1000, a = Math.abs(s);
  if (a < 45) return "just now";
  if (a < 3600) return rtf.format(Math.round(s / 60), "minute");
  if (a < 86400) return rtf.format(Math.round(s / 3600), "hour");
  return rtf.format(Math.round(s / 86400), "day");
}
const n = (x: number) => new Intl.NumberFormat("en", { notation: x >= 10000 ? "compact" : "standard", maximumFractionDigits: 1 }).format(x ?? 0);
const hours = (minutes: number) => (minutes >= 90 ? `${n(Math.round(minutes / 6) / 10)} h` : `${minutes} min`);
const pct = (x: number) => `${Math.round(x * 100)}%`;
const clock = (sec?: number) => sec == null || !isFinite(sec) ? "" : `${Math.floor(sec / 3600) ? `${Math.floor(sec / 3600)}:` : ""}${String(Math.floor(sec / 60) % 60).padStart(Math.floor(sec / 3600) ? 2 : 1, "0")}:${String(Math.floor(sec % 60)).padStart(2, "0")}`;
const rate = (ok: number, err: number) => (ok + err ? ok / (ok + err) : 1);
const DEVICE: Record<string, typeof Tv> = { tv: Tv, phone: Smartphone, android: Smartphone, web: Monitor };
const DeviceIcon = ({ d, className }: { d?: string; className?: string }) => { const I = DEVICE[d ?? ""] ?? Monitor; return <I className={cn("size-4 text-foreground/52", className)} aria-label={d} />; };

// ---- shell ----------------------------------------------------------------
const NAV: [string, [string, string, typeof Tv][]][] = [
  ["Watching", [["", "Overview", LayoutDashboard], ["live", "Live now", Radio], ["engagement", "Engagement", Flame], ["content", "Content", Clapperboard]]],
  ["Quality", [["sources", "Sources", Activity], ["app", "App health", Smartphone]]],
  ["People", [["users", "Users", Users], ["security", "Security", ShieldCheck], ["events", "Events", ScrollText]]],
  ["Infra", [["providers", "Providers", Server], ["ops", "Ops", Server]]],
];

export function AdminSidebar() {
  const path = usePathname();
  return (
    <Sidebar collapsible="icon" className="border-r-0">
      <SidebarHeader className="px-3 pt-4">
        <Link href="/admin" className="flex items-center gap-2 rounded-xl px-1 py-1 font-display text-xl font-bold tracking-tight">
          <ReelMark className="size-7 shrink-0" /><span className="group-data-[collapsible=icon]:hidden">Reelm</span>
          <span className="rounded-full bg-primary px-2 py-0.5 font-sans text-[10px] font-bold tracking-wide text-primary-foreground group-data-[collapsible=icon]:hidden">ADMIN</span>
        </Link>
      </SidebarHeader>
      <SidebarContent>
        {NAV.map(([group, items]) => (
          <SidebarGroup key={group}>
            <SidebarGroupLabel>{group}</SidebarGroupLabel>
            <SidebarMenu>
              {items.map(([slug, label, Icon]) => {
                const href = `/admin${slug ? `/${slug}` : ""}`, on = path === href;
                return (
                  <SidebarMenuItem key={slug}>
                    <SidebarMenuButton isActive={on} tooltip={label} render={<Link href={href} />}
                      className="data-active:bg-primary/14 data-active:font-semibold data-active:text-primary data-active:shadow-[inset_2px_0_0_#f2b45a]">
                      <Icon /><span>{label}</span>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                );
              })}
            </SidebarMenu>
          </SidebarGroup>
        ))}
      </SidebarContent>
      <SidebarFooter>
        <SidebarMenu><SidebarMenuItem><SidebarMenuButton tooltip="Back to Reelm" render={<Link href="/" />}><ArrowLeft /><span>Back to Reelm</span></SidebarMenuButton></SidebarMenuItem></SidebarMenu>
      </SidebarFooter>
    </Sidebar>
  );
}

function Head({ title, desc, at, reload, live }: { title: string; desc: string; at?: number; reload?: () => void; live?: boolean }) {
  const [, tick] = useState(0);
  useEffect(() => { const i = setInterval(() => tick((x) => x + 1), 15000); return () => clearInterval(i); }, []);
  return (
    <header className="flex flex-wrap items-end gap-x-4 gap-y-2 pb-6">
      <div className="w-full md:hidden"><SidebarTrigger className="-ml-1" /></div>
      <div className="min-w-0 flex-1">
        <h1 className="font-display text-[28px] leading-tight font-semibold tracking-tight md:text-[34px]">{title}</h1>
        <p className="mt-1 max-w-[62ch] text-sm text-foreground/60">{desc}</p>
      </div>
      {at && (
        <button onClick={reload} className="beam inline-flex h-9 items-center gap-2 rounded-full bg-white/6 px-3.5 text-xs text-foreground/64 hover:text-foreground">
          {live && <span className="relative flex size-2"><span className="absolute inset-0 animate-ping rounded-full bg-signal/70 motion-reduce:hidden" /><span className="relative size-2 rounded-full bg-signal" /></span>}
          {live ? "Live, " : ""}updated {ago(at)}<RefreshCw className="size-3.5" />
        </button>
      )}
    </header>
  );
}

function Panel({ title, hint, children, className, beam, action }: { title?: string; hint?: string; children: ReactNode; className?: string; beam?: boolean; action?: ReactNode }) {
  return (
    <section className={cn("relative overflow-hidden rounded-[18px] bg-slate p-5 ring-1 ring-white/6", className)}>
      {title && <div className="mb-4 flex flex-wrap items-baseline gap-x-3 gap-y-2"><h2 className="font-display text-lg font-semibold whitespace-nowrap tracking-tight">{title}</h2>{hint && <p className="text-xs text-foreground/48">{hint}</p>}{action && <div className="w-full sm:ml-auto sm:w-auto">{action}</div>}</div>}
      {children}
      {beam && <BorderBeam size={120} duration={9} colorFrom="#f2b45a" colorTo="#ffe2b0" />}
    </section>
  );
}

const Meter = ({ v, tone = "lamp" }: { v: number; tone?: "lamp" | "signal" | "coral" }) => (
  <div className="h-1.5 w-full overflow-hidden rounded-full bg-white/8" role="meter" aria-valuenow={Math.round(v * 100)} aria-valuemin={0} aria-valuemax={100}>
    <div className={cn("h-full rounded-full", tone === "lamp" ? "bg-primary" : tone === "signal" ? "bg-signal" : "bg-[#f07c6c]")} style={{ width: `${Math.max(2, Math.min(1, v) * 100)}%` }} />
  </div>
);

/** A ranked list with an inline bar: the shape most admin answers take. */
function Ranked({ rows, empty }: { rows: { label: ReactNode; value: number; shown?: ReactNode; sub?: ReactNode; tone?: "lamp" | "signal" | "coral" }[]; empty: string }) {
  if (!rows.length) return <Empty>{empty}</Empty>;
  const max = Math.max(...rows.map((r) => r.value), 1);
  return (
    <ol className="space-y-3">
      {rows.map((r, i) => (
        <li key={i} className="grid grid-cols-[1.5rem_1fr_auto] items-center gap-x-3 gap-y-1.5">
          <span className="text-right text-xs tabular-nums text-foreground/40">{i + 1}</span>
          <div className="min-w-0"><p className="truncate text-sm font-medium">{r.label}</p>{r.sub && <p className="truncate text-xs text-foreground/48">{r.sub}</p>}</div>
          <span className="text-sm tabular-nums text-foreground/80">{r.shown ?? n(r.value)}</span>
          <span /><div className="col-span-2"><Meter v={r.value / max} tone={r.tone} /></div>
        </li>
      ))}
    </ol>
  );
}

const Empty = ({ children }: { children: ReactNode }) => <p className="rounded-xl bg-white/4 px-4 py-6 text-center text-sm text-foreground/52">{children}</p>;
const Loading = () => <div className="grid gap-4 md:grid-cols-2" aria-busy><div className="skel h-40 rounded-[18px] md:col-span-2" /><div className="skel h-64 rounded-[18px]" /><div className="skel h-64 rounded-[18px]" /></div>;
function Failed({ error, reload }: { error: string; reload: () => void }) {
  return <Notice title="Couldn't load this page" body={/admin only/.test(error) ? "Your account isn't an admin." : error}><button onClick={reload} className="beam rounded-full bg-secondary px-5 py-2.5 text-sm font-medium">Try again</button></Notice>;
}

/** Fetch + standard head/loading/error frame for a section. */
function Section<T = any>({ path, title, desc, every, children }: { path: string; title: string; desc: string; every?: number; children: (d: T, reload: () => void) => ReactNode }) {
  const { data, error, at, reload } = useAdmin<T>(path, every);
  return (
    <div className="mx-auto w-full max-w-[1320px] px-4 pt-5 pb-16 md:px-8 md:pt-8">
      <Head title={title} desc={desc} at={at} reload={reload} live={!!every} />
      {data ? children(data, reload) : error ? <Failed error={error} reload={reload} /> : <Loading />}
    </div>
  );
}

// ---- events ---------------------------------------------------------------
const KIND_LABEL: Record<string, string> = {
  register: "signed up", login: "signed in", login_failed: "failed sign-in", logout: "signed out", active: "came back today",
  profile_create: "created a profile", profile_update: "edited a profile", profile_delete: "deleted a profile", history_push: "synced history",
  pair_start: "TV pairing started", pair_claim: "paired a TV", room_create: "opened a watch-together room", room_join: "joined a room", room_load: "room switched title",
  hub_connect: "connected", handoff: "handed off to another device", play: "started", play_error: "playback failed", source_used: "streamed from",
  heartbeat: "is watching", crash: "app crashed", search_empty: "searched with no results", update_offered: "was offered an update", update_installed: "installed an update",
};
const TONE: Record<string, string> = { login_failed: "bg-[#f07c6c]", play_error: "bg-[#f07c6c]", crash: "bg-[#f07c6c]", register: "bg-signal", play: "bg-primary", source_used: "bg-primary", heartbeat: "bg-primary" };
function what(e: any) {
  const d = e.detail ?? {};
  return d.title ?? d.query ?? (d.count != null ? `${d.count} ${d.count === 1 ? "entry" : "entries"}` : d.target ?? d.name ?? d.code ?? d.username ?? "");
}
function EventLine({ e }: { e: any }) {
  const d = e.detail ?? {};
  return (
    <div className="flex items-start gap-3 py-2.5">
      <span className={cn("mt-1.5 size-2 shrink-0 rounded-full", TONE[e.kind] ?? "bg-foreground/30")} />
      <div className="min-w-0 flex-1 text-sm">
        <p className="leading-snug"><span className="font-semibold">{e.username ?? (e.kind === "login_failed" ? d.username : "Someone")}</span>{" "}
          <span className="text-foreground/64">{KIND_LABEL[e.kind] ?? e.kind.replace(/_/g, " ")}</span>{" "}<span className="text-foreground/90">{what(e)}</span>
          {d.source && e.kind !== "heartbeat" && <span className="text-foreground/52"> on {d.source}</span>}</p>
        {d.error && <p className="mt-0.5 line-clamp-2 text-xs [overflow-wrap:anywhere] text-[#f07c6c]/90">{d.error}</p>}
      </div>
      <span className="flex shrink-0 items-center gap-1.5 text-xs text-foreground/44"><DeviceIcon d={e.device} className="size-3.5" />{ago(e.at)}</span>
    </div>
  );
}

// ---- charts ---------------------------------------------------------------
const trendConfig = { dau: { label: "Daily active", color: "#f2b45a" }, wau: { label: "Weekly active", color: "rgb(233 234 240 / 0.6)" }, signups: { label: "Sign-ups", color: "#7fd1b9" } } satisfies ChartConfig;
const shortDay = (d: string) => new Date(d + "T00:00:00Z").toLocaleDateString("en", { month: "short", day: "numeric", timeZone: "UTC" });

function Trend({ daily }: { daily: any[] }) {
  return (
    <ChartContainer config={trendConfig} className="aspect-auto h-[260px] w-full">
      <ComposedChart data={daily} margin={{ left: -6, right: 4, top: 8 }}>
        <defs><linearGradient id="dau-fill" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#f2b45a" stopOpacity={0.35} /><stop offset="100%" stopColor="#f2b45a" stopOpacity={0} /></linearGradient></defs>
        <CartesianGrid vertical={false} stroke="rgb(233 234 240 / 0.06)" />
        <XAxis dataKey="day" tickLine={false} axisLine={false} tickFormatter={shortDay} minTickGap={28} tick={{ fill: "rgb(233 234 240 / 0.45)", fontSize: 11 }} />
        <YAxis tickLine={false} axisLine={false} width={44} tick={{ fill: "rgb(233 234 240 / 0.45)", fontSize: 11 }} allowDecimals={false} />
        <ChartTooltip content={<ChartTooltipContent labelFormatter={(v) => shortDay(String(v))} />} />
        <Bar dataKey="signups" fill="var(--color-signups)" radius={[4, 4, 0, 0]} barSize={8} />
        <Area dataKey="dau" type="monotone" stroke="var(--color-dau)" strokeWidth={2} fill="url(#dau-fill)" />
        <Line dataKey="wau" type="monotone" stroke="var(--color-wau)" strokeWidth={1.5} strokeDasharray="4 4" dot={false} />
      </ComposedChart>
    </ChartContainer>
  );
}

const Legend = ({ items }: { items: [string, string][] }) => (
  <div className="flex flex-wrap gap-4 text-xs text-foreground/60">{items.map(([c, l]) => <span key={l} className="inline-flex items-center gap-1.5"><span className="size-2 rounded-full" style={{ background: c }} />{l}</span>)}</div>
);

// ---- sections -------------------------------------------------------------
function Overview() {
  const feed = useAdmin<{ events: any[] }>("events?limit=12", 10_000);
  return (
    <Section path="overview" title="Overview" desc="Who's using Reelm, how much, and whether playback is healthy. Activity covers the last 30 days.">
      {(d: any) => {
        const kpis: [string, number, string?][] = [
          ["Watching now", d.totals.watchingNow, "live"], ["Online, 15 min", d.totals.onlineNow], ["Accounts", d.totals.users], ["Profiles", d.totals.profiles],
          ["Devices seen", d.totals.devices], ["History entries", d.totals.history], ["Open rooms", d.totals.activeRooms],
        ];
        return (
          <div className="space-y-4">
            <BlurFade>
              <div className="grid grid-cols-2 overflow-hidden rounded-[18px] bg-slate ring-1 ring-white/6 sm:grid-cols-4 xl:grid-cols-7">
                {kpis.map(([label, v, live], i) => (
                  <div key={label} className={cn("relative border-white/6 px-5 py-4", i > 0 && "border-l", "max-sm:[&:nth-child(2n+1)]:border-l-0 max-sm:border-t max-sm:[&:nth-child(-n+2)]:border-t-0", live && "bg-primary/[0.07]", "max-sm:last:col-span-2")}>
                    <p className="flex items-center gap-1.5 text-xs text-foreground/56">{live && <span className="size-1.5 rounded-full bg-primary shadow-[0_0_10px_#f2b45a]" />}{label}</p>
                    <p className={cn("mt-1 font-display text-3xl font-semibold tabular-nums tracking-tight", live && "text-primary")}>{v ? <NumberTicker value={v} className="text-inherit" /> : 0}</p>
                  </div>
                ))}
              </div>
            </BlurFade>
            <div className="grid gap-4 xl:grid-cols-3">
              <Panel title="Activity" hint="last 30 days" className="xl:col-span-2" action={<Legend items={[["#f2b45a", "Daily active"], ["rgb(233 234 240 / 0.6)", "Weekly active"], ["#7fd1b9", "Sign-ups"]]} />}>
                <Trend daily={d.daily} />
              </Panel>
              <Panel title="Happening now" hint="refreshes every 10 s" beam>
                {feed.data?.events.length ? (
                  <div className="-my-2 max-h-[280px] divide-y divide-white/6 overflow-y-auto pr-1">
                    <AnimatePresence initial={false}>{feed.data.events.map((e) => <AnimatedListItem key={e.id}><EventLine e={e} /></AnimatedListItem>)}</AnimatePresence>
                  </div>
                ) : <Empty>{feed.error ?? "Nothing yet. Events appear here as people sign in and watch."}</Empty>}
              </Panel>
            </div>
            <div className="grid items-start gap-4 lg:grid-cols-2">
              <Panel title="Top titles" hint="plays, last 30 days">
                <Ranked empty="No plays reported yet." rows={d.topTitles.map((t: any) => ({ label: t.title, value: t.plays, sub: `${t.viewers} ${t.viewers === 1 ? "viewer" : "viewers"}` }))} />
              </Panel>
              <Panel title="Source health" hint="streams that started vs failed">
                <SourceRows rows={d.sources} />
              </Panel>
            </div>
          </div>
        );
      }}
    </Section>
  );
}

function SourceRows({ rows }: { rows: any[] }) {
  if (!rows.length) return <Empty>No playback reported yet.</Empty>;
  return (
    <ul className="space-y-3.5">
      {rows.map((r) => {
        const v = rate(r.ok, r.errors);
        return (
          <li key={r.source + (r.host ?? "")} className="grid items-center gap-x-4 gap-y-1.5 sm:grid-cols-[1fr_auto]">
            <p className="truncate text-sm font-medium">{r.source}{r.host ? <span className="ml-2 text-xs font-normal text-foreground/44">{r.host}</span> : null}</p>
            <p className="text-sm tabular-nums"><span className={v < 0.8 ? "text-[#f07c6c]" : v < 0.95 ? "text-primary" : "text-signal"}>{pct(v)}</span><span className="ml-2 text-xs text-foreground/44">{n(r.ok)} ok, {n(r.errors)} failed{r.ttff ? `, ${(r.ttff / 1000).toFixed(1)} s to first frame` : ""}</span></p>
            <div className="sm:col-span-2"><Meter v={v} tone={v < 0.8 ? "coral" : v < 0.95 ? "lamp" : "signal"} /></div>
          </li>
        );
      })}
    </ul>
  );
}

function Live() {
  return (
    <Section path="live" every={10_000} title="Live now" desc="Everyone with a playback heartbeat in the last 2 minutes, and the watch-together rooms that are open.">
      {(d: any) => (
        <div className="space-y-4">
          <Panel title="Watching" hint={`${d.watching.length} ${d.watching.length === 1 ? "stream" : "streams"}`} beam={d.watching.length > 0}>
            {d.watching.length ? (
              <ul className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
                {d.watching.map((w: any, i: number) => {
                  const x = w.detail ?? {}, f = x.duration ? x.position / x.duration : 0;
                  return (
                    <BlurFade key={`${w.user_id}${w.device}${i}`} delay={i * 0.04}>
                      <li className="rounded-[14px] bg-haze/70 p-4">
                        <div className="flex items-center gap-3">
                          <Avatar id={w.avatar ?? "grinning_face"} size={36} />
                          <div className="min-w-0 flex-1"><p className="truncate text-sm font-semibold">{w.profile ?? w.username}</p><p className="truncate text-xs text-foreground/52">@{w.username}</p></div>
                          <span className="inline-flex items-center gap-1.5 rounded-full bg-white/6 px-2.5 py-1 text-xs text-foreground/64"><DeviceIcon d={w.device} className="size-3.5" />{w.device}{w.app_version && w.app_version !== "web" ? ` ${w.app_version}` : ""}</span>
                        </div>
                        <p className="mt-3 line-clamp-1 font-display text-base font-semibold">{x.title ?? "Untitled"}</p>
                        <p className="text-xs text-foreground/52">{[x.episode && `Episode ${x.episode}`, x.source, x.quality].filter(Boolean).join(", ")}</p>
                        <div className="mt-3 flex items-center gap-3"><Meter v={f} /><span className="shrink-0 text-xs tabular-nums text-foreground/52">{clock(x.position)} of {clock(x.duration)}</span></div>
                      </li>
                    </BlurFade>
                  );
                })}
              </ul>
            ) : <Empty>Nobody is watching right now.</Empty>}
          </Panel>
          <Panel title="Watch together" hint="open rooms">
            {d.rooms.length ? (
              <ul className="divide-y divide-white/6">
                {d.rooms.map((r: any) => (
                  <li key={r.code} className="flex flex-wrap items-center gap-3 py-3">
                    <code className="rounded-full bg-primary/12 px-2.5 py-1 text-xs font-semibold tracking-wider text-primary">{r.code}</code>
                    <p className="min-w-0 flex-1 truncate text-sm font-medium">{r.title ?? "Choosing a title"}</p>
                    <div className="flex -space-x-2">{r.members.map((m: any, i: number) => <span key={i} title={m.name}><Avatar id={m.avatar || "grinning_face"} size={28} /></span>)}</div>
                    <span className="w-28 text-right text-xs text-foreground/48">{r.members.length ? `${r.members.length} in room` : `empty, ${ago(r.updated_at)}`}</span>
                  </li>
                ))}
              </ul>
            ) : <Empty>No rooms are open.</Empty>}
          </Panel>
        </div>
      )}
    </Section>
  );
}

const DOW = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
function Heatmap({ cells }: { cells: any[] }) {
  const m = new Map(cells.map((c) => [`${c.dow}:${c.hour}`, c.minutes])), max = Math.max(1, ...cells.map((c) => c.minutes));
  return (
    <div className="overflow-x-auto">
      <div className="grid min-w-[560px] grid-cols-[2.5rem_repeat(24,1fr)] gap-[3px] text-[10px] text-foreground/40">
        <span />{Array.from({ length: 24 }, (_, h) => <span key={h} className="text-center">{h % 3 === 0 ? h : ""}</span>)}
        {DOW.map((d, dow) => (
          <div key={d} className="contents">
            <span className="self-center text-xs">{d}</span>
            {Array.from({ length: 24 }, (_, h) => {
              const v = m.get(`${dow}:${h}`) ?? 0;
              return <span key={h} title={`${d} ${h}:00 UTC, ${hours(v)}`} className="aspect-square rounded-[3px]" style={{ background: v ? `rgb(242 180 90 / ${0.08 + 0.92 * (v / max)})` : "rgb(233 234 240 / 0.04)" }} />;
            })}
          </div>
        ))}
      </div>
    </div>
  );
}

function Engagement() {
  return (
    <Section path="engagement" title="Engagement" desc="Watch time comes from one heartbeat per minute of playback. Completion uses synced history: 90% watched counts as finished.">
      {(d: any) => {
        const total = d.platforms.reduce((a: number, p: any) => a + p.minutes, 0) || 1;
        return (
          <div className="grid items-start gap-4 lg:grid-cols-2">
            <Panel title="When people watch" hint="weekday by hour, UTC" className="lg:col-span-2"><Heatmap cells={d.heatmap} /></Panel>
            <Panel title="Platform split" hint="share of watch time">
              <div className="mb-4 flex h-3 overflow-hidden rounded-full">{d.platforms.map((p: any, i: number) => <span key={p.platform} style={{ width: `${(p.minutes / total) * 100}%`, background: ["#f2b45a", "#7fd1b9", "rgb(233 234 240 / 0.5)", "#f07c6c"][i % 4] }} />)}</div>
              <ul className="space-y-2">{d.platforms.map((p: any, i: number) => (
                <li key={p.platform} className="flex items-center gap-3 text-sm"><span className="size-2 rounded-full" style={{ background: ["#f2b45a", "#7fd1b9", "rgb(233 234 240 / 0.5)", "#f07c6c"][i % 4] }} /><DeviceIcon d={p.platform} /><span className="flex-1 capitalize">{p.platform}</span><span className="tabular-nums text-foreground/64">{pct(p.minutes / total)}, {hours(p.minutes)}, {p.users} people</span></li>
              ))}</ul>
              {!d.platforms.length && <Empty>No watch time yet.</Empty>}
            </Panel>
            <Panel title="Binge streaks" hint="consecutive days watched">
              <Ranked empty="No streaks yet." rows={d.binge.map((b: any) => ({ label: b.username, value: b.longest, shown: `${b.longest} days`, sub: b.current ? `on a ${b.current}-day streak now` : "streak ended", tone: b.current ? "lamp" : "signal" }))} />
            </Panel>
            <Panel title="Watch time by account"><Ranked empty="No watch time yet." rows={d.perUser.map((u: any) => ({ label: u.username, value: u.minutes, shown: hours(u.minutes) }))} /></Panel>
            <Panel title="Watch time by profile">
              <Ranked empty="No watch time yet." rows={d.perProfile.map((p: any) => ({ label: <span className="inline-flex items-center gap-2"><Avatar id={p.avatar} size={22} />{p.name}{p.kids ? <span className="rounded-full bg-signal/15 px-1.5 text-[10px] text-signal">Kids</span> : null}</span>, value: p.minutes, shown: hours(p.minutes), sub: `@${p.username}` }))} />
            </Panel>
            <Panel title="Completion by title" hint="finished, of everyone who started" className="lg:col-span-2">
              <Ranked empty="No synced history yet." rows={d.completion.map((c: any) => ({ label: c.title, value: c.completed / Math.max(1, c.starts), shown: pct(c.completed / Math.max(1, c.starts)), sub: `${c.completed} of ${c.starts} finished`, tone: "signal" }))} />
            </Panel>
          </div>
        );
      }}
    </Section>
  );
}

function Content() {
  return (
    <Section path="content" title="Content" desc="What gets watched, what gets dropped, and what people looked for but couldn't find.">
      {(d: any) => {
        const total = d.kinds.reduce((a: number, k: any) => a + k.minutes, 0) || 1;
        return (
          <div className="grid gap-4 lg:grid-cols-2">
            <Panel title="Most watched" hint="watch time, 30 days"><Ranked empty="No watch time yet." rows={d.watched.map((t: any) => ({ label: t.title, value: t.minutes, shown: hours(t.minutes), sub: `${t.viewers} viewers` }))} /></Panel>
            <Panel title="Most abandoned" hint="started, under 20% watched"><Ranked empty="Nothing abandoned yet." rows={d.abandoned.map((t: any) => ({ label: t.title, value: t.abandoned, shown: `${t.abandoned} profiles`, tone: "coral" }))} /></Panel>
            <Panel title="What people watch" hint="share of watch time">
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">{d.kinds.map((k: any) => (
                <div key={k.kind} className="rounded-[14px] bg-haze/60 p-4"><p className="text-xs capitalize text-foreground/56">{k.kind === "live" ? "Live TV" : k.kind}</p><p className="mt-1 font-display text-2xl font-semibold">{pct(k.minutes / total)}</p><p className="text-xs text-foreground/44">{hours(k.minutes)}</p></div>
              ))}</div>
              {!d.kinds.length && <Empty>No watch time yet.</Empty>}
            </Panel>
            <Panel title="Searches with no results" hint="worth adding a source for">
              {d.searches.length ? <ul className="flex flex-wrap gap-2">{d.searches.map((s: any) => <li key={s.query} className="inline-flex items-center gap-2 rounded-full bg-white/6 py-1.5 pr-1.5 pl-3.5 text-sm">{s.query}<span className="rounded-full bg-primary/14 px-2 text-xs tabular-nums text-primary">{s.times}</span></li>)}</ul> : <Empty>Every search found something.</Empty>}
            </Panel>
          </div>
        );
      }}
    </Section>
  );
}

const SRC_COLORS = ["#f2b45a", "#7fd1b9", "#9db4ff", "#f07c6c", "#e9eaf0", "#c79bf2"];
function Sources() {
  return (
    <Section path="sources" title="Sources" desc="Playback health by source and host over 14 days. A source is flagged when fewer than half of its streams started in the last hour (5 tries or more).">
      {(d: any) => {
        const names: string[] = [...new Set<string>(d.daily.map((r: any) => r.source))].slice(0, 6);
        const byDay = Object.values(d.daily.reduce((acc: any, r: any) => { (acc[r.day] ??= { day: r.day })[r.source] = Math.round(rate(r.ok, r.errors) * 100); return acc; }, {}));
        const cfg = Object.fromEntries(names.map((s, i) => [s, { label: s, color: SRC_COLORS[i] }])) satisfies ChartConfig;
        return (
          <div className="space-y-4">
            {d.alerts.map((a: any) => (
              <div key={a.source} role="alert" className="flex items-center gap-3 rounded-[18px] bg-[#f07c6c]/12 px-5 py-4 ring-1 ring-[#f07c6c]/30">
                <AlertTriangle className="size-5 shrink-0 text-[#f07c6c]" />
                <p className="text-sm"><span className="font-semibold">{a.source} dropped.</span> Only {a.ok} of {a.ok + a.errors} streams started in the last hour.</p>
              </div>
            ))}
            <div className="grid gap-4 sm:grid-cols-2">
              <Panel><p className="text-xs text-foreground/56">HEVC failures, 14 days</p><p className="mt-1 font-display text-3xl font-semibold tabular-nums"><NumberTicker value={d.hevcFailures || 0} className="text-inherit" /></p><p className="mt-1 text-xs text-foreground/44">Browsers and TVs without H.265 decode</p></Panel>
              <Panel><p className="text-xs text-foreground/56">Dolby audio failures, 14 days</p><p className="mt-1 font-display text-3xl font-semibold tabular-nums"><NumberTicker value={d.dolbyFailures || 0} className="text-inherit" /></p><p className="mt-1 text-xs text-foreground/44">AC-3 / E-AC-3 tracks that failed to play</p></Panel>
            </div>
            <Panel title="Success rate over time" hint="% of streams that started, per day" action={<Legend items={names.map((s, i) => [SRC_COLORS[i], s])} />}>
              {byDay.length ? (
                <ChartContainer config={cfg} className="aspect-auto h-[240px] w-full">
                  <LineChart data={byDay} margin={{ left: -6, right: 4, top: 8 }}>
                    <CartesianGrid vertical={false} stroke="rgb(233 234 240 / 0.06)" />
                    <XAxis dataKey="day" tickLine={false} axisLine={false} tickFormatter={shortDay} minTickGap={28} tick={{ fill: "rgb(233 234 240 / 0.45)", fontSize: 11 }} />
                    <YAxis domain={[0, 100]} tickLine={false} axisLine={false} width={44} tickFormatter={(v) => `${v}%`} tick={{ fill: "rgb(233 234 240 / 0.45)", fontSize: 11 }} />
                    <ChartTooltip content={<ChartTooltipContent labelFormatter={(v) => shortDay(String(v))} />} />
                    {names.map((s) => <Line key={s} dataKey={s} type="monotone" stroke={`var(--color-${s})`} strokeWidth={2} dot={false} connectNulls />)}
                  </LineChart>
                </ChartContainer>
              ) : <Empty>No playback reported yet.</Empty>}
            </Panel>
            <div className="grid gap-4 lg:grid-cols-2">
              <Panel title="By source and host"><SourceRows rows={d.bySource} /></Panel>
              <Panel title="Failures by codec"><Ranked empty="No failures." rows={d.codecs.map((c: any) => ({ label: c.codec, value: c.errors, tone: "coral" }))} /></Panel>
            </div>
            <Panel title="Recent playback errors">
              {d.recent.length ? <div className="-my-2 divide-y divide-white/6">{d.recent.slice(0, 25).map((e: any) => <EventLine key={e.id} e={e} />)}</div> : <Empty>No playback errors. Nice.</Empty>}
            </Panel>
          </div>
        );
      }}
    </Section>
  );
}

function AppHealth() {
  const [open, setOpen] = useState<number | null>(null);
  return (
    <Section path="app" title="App health" desc="Crashes from the phone and TV apps, which versions people run, and whether in-app updates land.">
      {(d: any) => (
        <div className="grid gap-4 lg:grid-cols-2">
          <Panel title="Crash reports" hint="sent on the next app start" className="lg:col-span-2">
            {d.crashes.length ? (
              <ul className="divide-y divide-white/6">{d.crashes.map((c: any) => (
                <li key={c.id} className="py-3">
                  <button onClick={() => setOpen(open === c.id ? null : c.id)} aria-expanded={open === c.id} className="flex w-full items-center gap-3 text-left text-sm">
                    <span className="size-2 shrink-0 rounded-full bg-[#f07c6c]" />
                    <span className="min-w-0 flex-1 truncate font-medium">{String(c.detail?.trace ?? "").split("\n")[0]}</span>
                    <span className="shrink-0 text-xs text-foreground/48">{c.username ?? "unknown"}, {c.device} {c.app_version}, {ago(c.at)}</span>
                  </button>
                  {open === c.id && <pre className="mt-3 max-h-80 overflow-auto rounded-xl bg-dusk p-4 text-xs leading-relaxed text-foreground/72">{c.detail?.trace}</pre>}
                </li>
              ))}</ul>
            ) : <Empty>No crashes reported.</Empty>}
          </Panel>
          <Panel title="Version adoption" hint="people per version, 30 days">
            <Ranked empty="No versions reported yet." rows={d.versions.map((v: any) => ({ label: <span className="inline-flex items-center gap-2"><DeviceIcon d={v.platform} />{v.version}</span>, value: v.users, shown: `${v.users} people` }))} />
          </Panel>
          <div className="space-y-4">
            <Panel title="In-app updates" hint="installed after being offered">
              <Ranked empty="No update prompts yet." rows={d.updates.map((u: any) => ({ label: u.version, value: u.installed / Math.max(1, u.offered), shown: pct(u.installed / Math.max(1, u.offered)), sub: `${u.installed} of ${u.offered} installed`, tone: "signal" }))} />
            </Panel>
            <Panel title="Reelm Helper on web" hint="browser extension versions">
              <Ranked empty="No web playback yet." rows={d.helpers.map((h: any) => ({ label: h.helper === "none" ? "Not installed" : h.helper, value: h.users, shown: `${h.users} people` }))} />
            </Panel>
          </div>
        </div>
      )}
    </Section>
  );
}

function Security() {
  return (
    <Section path="security" title="Security" desc="Failed sign-ins, devices seen for the first time this week, open sessions, and what kids profiles watch.">
      {(d: any) => (
        <div className="grid gap-4 lg:grid-cols-2">
          <Panel title="Failed sign-ins" hint="latest 50">
            {d.failed.length ? <ul className="divide-y divide-white/6">{d.failed.map((f: any, i: number) => (
              <li key={i} className="flex items-center gap-3 py-2.5 text-sm"><span className="size-2 rounded-full bg-[#f07c6c]" /><span className="flex-1 font-medium">{f.username ?? "?"}{!f.known && <span className="ml-2 text-xs font-normal text-foreground/44">no such account</span>}</span><span className="text-xs text-foreground/48">{f.ip_country ?? "??"}, {ago(f.at)}</span></li>
            ))}</ul> : <Empty>No failed sign-ins.</Empty>}
          </Panel>
          <Panel title="New devices" hint="first seen in the last 7 days">
            {d.newDevices.length ? <ul className="divide-y divide-white/6">{d.newDevices.map((x: any, i: number) => (
              <li key={i} className="flex items-center gap-3 py-2.5 text-sm"><DeviceIcon d={x.device} /><span className="min-w-0 flex-1"><span className="font-medium">{x.username}</span><span className="block truncate text-xs text-foreground/44">{x.ua}</span></span><span className="shrink-0 text-xs text-foreground/48">{x.ip_country ?? "??"}, {ago(x.first_at)}</span></li>
            ))}</ul> : <Empty>No new devices this week.</Empty>}
          </Panel>
          <Panel title="Sessions per account" hint="sign out one session from the user's page">
            <Ranked empty="No sessions." rows={d.sessions.map((x: any) => ({ label: x.username, value: x.sessions, shown: `${x.sessions} signed in`, sub: `last used ${ago(x.last_used_at)}` }))} />
          </Panel>
          <Panel title="Kids profiles" hint="watch time, 30 days">
            {d.kids.length ? <ul className="divide-y divide-white/6">{d.kids.map((k: any) => (
              <li key={k.id} className="flex items-center gap-3 py-2.5"><Avatar id={k.avatar} size={32} /><span className="min-w-0 flex-1 text-sm"><span className="font-medium">{k.name}</span> <span className="text-xs text-foreground/44">@{k.username}</span><span className="block truncate text-xs text-foreground/52">{k.last_title ? `Last watched ${k.last_title}, ${ago(k.last_at)}` : "Nothing watched yet"}</span></span><span className="text-sm tabular-nums text-foreground/72">{hours(k.minutes)}</span></li>
            ))}</ul> : <Empty>No kids profiles.</Empty>}
          </Panel>
        </div>
      )}
    </Section>
  );
}

// ---- users ----------------------------------------------------------------
type Confirm = { title: string; body: string; cta: string; danger?: boolean; run: () => Promise<void> } | null;

function UserSheet({ id, onClose, onChanged }: { id: string | null; onClose: () => void; onChanged: () => void }) {
  const { s } = useSession();
  const { data, reload } = useAdmin<any>(id ? `users/${id}` : null);
  const [confirm, setConfirm] = useState<Confirm>(null), [busy, setBusy] = useState(false), [temp, setTemp] = useState("");
  const u = data?.user?.id === id ? data.user : null;
  const act = (title: string, body: string, cta: string, path: string, done: string, opts: { danger?: boolean; method?: string } = {}) => setConfirm({
    title, body, cta, danger: opts.danger,
    run: async () => {
      const r = await adminAct(s.token, path, opts.method);
      if (r?.password) setTemp(r.password);
      toast.success(done);
      if (opts.method === "DELETE" && path.startsWith("users/")) onClose(); else reload();
      onChanged();
    },
  });
  const btn = "beam inline-flex h-9 items-center gap-2 rounded-full bg-white/8 px-3.5 text-sm hover:bg-white/12 [&_svg]:size-4";
  return (
    <Sheet open={!!id} onOpenChange={(o) => !o && onClose()}>
      <SheetContent className="w-full gap-0 overflow-y-auto border-white/6 bg-dusk p-0 sm:max-w-xl data-[side=right]:sm:max-w-xl">
        {!u ? <div className="space-y-3 p-6"><div className="skel h-16 rounded-2xl" /><div className="skel h-40 rounded-2xl" /></div> : (
          <>
            <SheetHeader className="lamp gap-3 p-6 pb-4">
              <div className="flex items-center gap-4">
                <Avatar id={data.profiles[0]?.avatar ?? "grinning_face"} size={56} ring={!!u.is_admin} />
                <div className="min-w-0">
                  <SheetTitle className="font-display text-2xl font-semibold">@{u.username}</SheetTitle>
                  <SheetDescription>Joined {ago(u.created_at)}, last seen {ago(u.last_seen_at)}</SheetDescription>
                  <div className="mt-2 flex gap-1.5">
                    {!!u.is_admin && <span className="rounded-full bg-primary/15 px-2.5 py-0.5 text-xs font-medium text-primary">Admin</span>}
                    {!!u.disabled && <span className="rounded-full bg-[#f07c6c]/15 px-2.5 py-0.5 text-xs font-medium text-[#f07c6c]">Disabled</span>}
                    <span className="rounded-full bg-white/8 px-2.5 py-0.5 text-xs text-foreground/64">{u.sessions} {u.sessions === 1 ? "session" : "sessions"}</span>
                  </div>
                </div>
              </div>
              <div className="flex flex-wrap gap-2 pt-2">
                {u.disabled
                  ? <button className={btn} onClick={() => act("Enable this account?", `@${u.username} can sign in again.`, "Enable", `users/${u.id}/enable`, "Account enabled")}><UserCheck />Enable</button>
                  : <button className={btn} onClick={() => act("Disable this account?", `@${u.username} is signed out everywhere and can't sign in until you enable the account again.`, "Disable", `users/${u.id}/disable`, "Account disabled", { danger: true })}><UserX />Disable</button>}
                <button className={btn} onClick={() => act("Sign out everywhere?", `Every device signed in as @${u.username} is signed out.`, "Sign out all", `users/${u.id}/revoke`, "Signed out of all devices")}><LogOut />Sign out all</button>
                <button className={btn} onClick={() => act("Reset the password?", `@${u.username} gets a new temporary password and is signed out everywhere. You'll see the password once.`, "Reset password", `users/${u.id}/reset_password`, "Password reset")}><KeyRound />Reset password</button>
                <button className={btn} onClick={() => act(u.is_admin ? "Remove admin?" : "Make admin?", u.is_admin ? `@${u.username} loses access to this dashboard.` : `@${u.username} can see everything here and act on every account.`, u.is_admin ? "Remove admin" : "Make admin", `users/${u.id}/${u.is_admin ? "remove_admin" : "make_admin"}`, u.is_admin ? "Admin removed" : "Admin granted")}>{u.is_admin ? <ShieldOff /> : <ShieldCheck />}{u.is_admin ? "Remove admin" : "Make admin"}</button>
                <button className={cn(btn, "text-[#f07c6c] hover:bg-[#f07c6c]/12")} onClick={() => act("Delete this account?", `@${u.username}, all profiles and all watch history are deleted. This can't be undone.`, "Delete account", `users/${u.id}`, "Account deleted", { danger: true, method: "DELETE" })}><Trash2 />Delete</button>
              </div>
            </SheetHeader>
            <Tabs defaultValue="profiles" className="px-6 pb-8">
              <TabsList className="w-full justify-start overflow-x-auto"><TabsTrigger value="profiles">Profiles</TabsTrigger><TabsTrigger value="devices">Devices</TabsTrigger><TabsTrigger value="sessions">Sessions</TabsTrigger><TabsTrigger value="history">History</TabsTrigger><TabsTrigger value="events">Events</TabsTrigger></TabsList>
              <TabsContent value="profiles" className="pt-4">
                <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3">{data.profiles.map((p: any) => (
                  <li key={p.id} className="flex flex-col items-center gap-2 rounded-[14px] bg-slate p-4 text-center"><Avatar id={p.avatar} size={56} /><span className="text-sm font-medium">{p.name}</span>{p.kids && <span className="rounded-full bg-signal/15 px-2 text-[11px] text-signal">Kids</span>}</li>
                ))}</ul>
              </TabsContent>
              <TabsContent value="devices" className="pt-4">
                {data.devices.length ? <ul className="divide-y divide-white/6">{data.devices.map((x: any, i: number) => (
                  <li key={i} className="flex items-center gap-3 py-3"><DeviceIcon d={x.device} className="size-5" /><div className="min-w-0 flex-1 text-sm"><p className="font-medium capitalize">{x.device ?? "Unknown"} <span className="text-xs font-normal normal-case text-foreground/44">{x.country}</span></p><p className="truncate text-xs text-foreground/44">{x.ua}</p></div><span className="shrink-0 text-right text-xs text-foreground/52">{x.events} events<br />{ago(x.last_at)}</span></li>
                ))}</ul> : <Empty>No devices recorded.</Empty>}
              </TabsContent>
              <TabsContent value="sessions" className="pt-4">
                {data.sessions.length ? <ul className="divide-y divide-white/6">{data.sessions.map((x: any) => (
                  <li key={x.id} className="flex items-center gap-3 py-3"><DeviceIcon d={x.device} className="size-5" /><div className="min-w-0 flex-1 text-sm"><p className="font-medium">Signed in {ago(x.created_at)}</p><p className="truncate text-xs text-foreground/44">Used {ago(x.last_used_at)}, {x.ua ?? "unknown client"}</p></div>
                    <button className={cn(btn, "h-8 px-3 text-xs")} onClick={() => act("Sign out this session?", "That device is signed out. Others stay signed in.", "Sign out", `sessions/${x.id}`, "Session signed out", { method: "DELETE" })}>Sign out</button></li>
                ))}</ul> : <Empty>Not signed in anywhere.</Empty>}
              </TabsContent>
              <TabsContent value="history" className="pt-4">
                {data.history.length ? <ol className="relative space-y-4 border-l border-white/10 pl-5">{data.history.map((h: any) => {
                  const f = h.duration ? h.position / h.duration : 0, p = data.profiles.find((x: any) => x.id === h.profile_id);
                  return (
                    <li key={h.profile_id + h.key} className="relative"><span className="absolute top-1.5 -left-[25px] size-2.5 rounded-full bg-primary ring-4 ring-dusk" />
                      <p className="text-sm font-medium">{h.payload?.title ?? h.key}</p>
                      <p className="text-xs text-foreground/48">{[p?.name, h.payload?.ep && `Episode ${h.payload.ep}`, h.payload?.season && `S${h.payload.season} E${h.payload.episode}`, ago(h.updated_at)].filter(Boolean).join(", ")}</p>
                      <div className="mt-2 flex items-center gap-2"><Meter v={f} /><span className="text-xs tabular-nums text-foreground/48">{pct(f)}</span></div>
                    </li>
                  );
                })}</ol> : <Empty>No watch history.</Empty>}
              </TabsContent>
              <TabsContent value="events" className="pt-2">
                {data.events.length ? <div className="divide-y divide-white/6">{data.events.map((e: any) => <EventLine key={e.id} e={{ ...e, username: u.username }} />)}</div> : <Empty>No events.</Empty>}
              </TabsContent>
            </Tabs>
          </>
        )}
      </SheetContent>
      <Dialog open={!!confirm} onOpenChange={(o) => !o && !busy && setConfirm(null)}>
        <DialogContent className="rounded-[24px] border-white/8 bg-haze">
          <DialogHeader><DialogTitle className="font-display text-xl">{confirm?.title}</DialogTitle><DialogDescription>{confirm?.body}</DialogDescription></DialogHeader>
          <DialogFooter>
            <button className="beam h-10 rounded-full px-5 text-sm text-foreground/72 hover:bg-white/8" onClick={() => setConfirm(null)} disabled={busy}>Cancel</button>
            <button disabled={busy} className={cn("beam h-10 rounded-full px-5 text-sm font-semibold disabled:opacity-50", confirm?.danger ? "bg-[#f07c6c] text-[#1a0d0a]" : "bg-primary text-primary-foreground")}
              onClick={async () => { setBusy(true); try { await confirm!.run(); setConfirm(null); } catch (e: any) { toast.error(e.message ?? "That didn't work"); } finally { setBusy(false); } }}>{busy ? "Working" : confirm?.cta}</button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <Dialog open={!!temp} onOpenChange={(o) => !o && setTemp("")}>
        <DialogContent className="rounded-[24px] border-white/8 bg-haze">
          <DialogHeader><DialogTitle className="font-display text-xl">Temporary password</DialogTitle><DialogDescription>Share it with the person privately. It won&apos;t be shown again.</DialogDescription></DialogHeader>
          <code className="block rounded-xl bg-dusk px-4 py-3 text-center font-mono text-xl tracking-widest text-primary select-all">{temp}</code>
          <DialogFooter><button className="beam h-10 rounded-full bg-primary px-5 text-sm font-semibold text-primary-foreground" onClick={() => { navigator.clipboard?.writeText(temp); toast.success("Copied"); setTemp(""); }}>Copy and close</button></DialogFooter>
        </DialogContent>
      </Dialog>
    </Sheet>
  );
}

/** New account form; the same username/password rules as sign-up are enforced by the server. */
function CreateUser({ onDone }: { onDone: () => void }) {
  const { s } = useSession();
  const [open, setOpen] = useState(false), [busy, setBusy] = useState(false);
  const [f, setF] = useState({ username: "", password: "", admin: false });
  const field = "beam h-11 w-full rounded-xl bg-white/6 px-4 text-sm outline-none placeholder:text-foreground/40";
  const submit = async (e: React.FormEvent) => {
    e.preventDefault(); setBusy(true);
    try {
      const { user } = await adminCreate(s.token, { ...f, username: f.username.trim().toLowerCase() });
      toast.success(`${user.is_admin ? "Admin" : "User"} @${user.username} created`);
      setOpen(false); setF({ username: "", password: "", admin: false }); onDone();
    } catch (err: any) { toast.error(err.message ?? "Couldn't create the account"); } finally { setBusy(false); }
  };
  return (
    <>
      <button onClick={() => setOpen(true)} className="beam flex h-10 items-center gap-2 rounded-full bg-primary px-4 text-sm font-semibold text-primary-foreground [&_svg]:size-4"><UserPlus />New user</button>
      <Dialog open={open} onOpenChange={(o) => !busy && setOpen(o)}>
        <DialogContent className="rounded-[24px] border-white/8 bg-haze">
          <DialogHeader><DialogTitle className="font-display text-xl">Create an account</DialogTitle>
            <DialogDescription>Share the username and password with the person. They can change their profiles after signing in.</DialogDescription></DialogHeader>
          <form onSubmit={submit} className="space-y-3">
            <input required autoFocus value={f.username} onChange={(e) => setF({ ...f, username: e.target.value })} placeholder="Username (a-z, 0-9, _)" aria-label="Username" pattern="[A-Za-z0-9_]{3,32}" className={field} />
            <input required type="text" minLength={8} value={f.password} onChange={(e) => setF({ ...f, password: e.target.value })} placeholder="Password (8+ characters)" aria-label="Password" className={field} />
            <label className="flex cursor-pointer items-center gap-3 rounded-xl bg-white/4 px-4 py-3 text-sm">
              <input type="checkbox" checked={f.admin} onChange={(e) => setF({ ...f, admin: e.target.checked })} className="size-4 accent-[#F2B45A]" />
              <span><span className="font-medium">Make this account an admin</span><span className="block text-xs text-foreground/52">Admins see this dashboard and can manage every account.</span></span>
            </label>
            <DialogFooter>
              <button type="button" onClick={() => setOpen(false)} disabled={busy} className="beam h-10 rounded-full px-5 text-sm hover:bg-white/8">Cancel</button>
              <button type="submit" disabled={busy} className="beam h-10 rounded-full bg-primary px-5 text-sm font-semibold text-primary-foreground disabled:opacity-60">{busy ? "Creating" : f.admin ? "Create admin" : "Create user"}</button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}

function UsersPage() {
  const [q, setQ] = useState(""), [term, setTerm] = useState(""), [page, setPage] = useState(0), [open, setOpen] = useState<string | null>(null);
  useEffect(() => { const t = setTimeout(() => { setTerm(q.trim()); setPage(0); }, 250); return () => clearTimeout(t); }, [q]);
  const per = 25;
  return (
    <>
      <Section path={`users?q=${encodeURIComponent(term)}&limit=${per}&offset=${page * per}`} title="Users" desc="Every account. Open one to see its profiles, devices, sessions and history, or to act on it.">
        {(d: any, reload) => (
          <Panel className="p-0">
            <div className="flex flex-wrap items-center gap-3 border-b border-white/6 p-4">
              <label className="relative min-w-0 flex-1"><Search className="pointer-events-none absolute top-1/2 left-3.5 size-4 -translate-y-1/2 text-foreground/44" />
                <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search usernames" aria-label="Search usernames" className="beam h-10 w-full rounded-full bg-white/6 pr-4 pl-10 text-sm outline-none placeholder:text-foreground/40" /></label>
              <span className="text-xs text-foreground/48">{d.total} {d.total === 1 ? "account" : "accounts"}</span>
              <CreateUser onDone={reload} />
            </div>
            <Table>
              <TableHeader><TableRow className="border-white/6 hover:bg-transparent"><TableHead className="pl-5">Account</TableHead><TableHead className="hidden sm:table-cell">Profiles</TableHead><TableHead className="hidden md:table-cell">Sessions</TableHead><TableHead className="hidden md:table-cell">Joined</TableHead><TableHead className="pr-5 text-right">Last seen</TableHead></TableRow></TableHeader>
              <TableBody>
                {d.users.map((u: any) => (
                  <TableRow key={u.id} onClick={() => setOpen(u.id)} className="cursor-pointer border-white/6 hover:bg-white/4" tabIndex={0} onKeyDown={(e) => e.key === "Enter" && setOpen(u.id)}>
                    <TableCell className="pl-5 font-medium">@{u.username}
                      {!!u.is_admin && <span className="ml-2 rounded-full bg-primary/15 px-2 py-0.5 text-[11px] text-primary">Admin</span>}
                      {!!u.disabled && <span className="ml-2 rounded-full bg-[#f07c6c]/15 px-2 py-0.5 text-[11px] text-[#f07c6c]">Disabled</span>}</TableCell>
                    <TableCell className="hidden tabular-nums sm:table-cell">{u.profiles}</TableCell>
                    <TableCell className="hidden tabular-nums md:table-cell">{u.sessions}</TableCell>
                    <TableCell className="hidden text-foreground/60 md:table-cell">{ago(u.created_at)}</TableCell>
                    <TableCell className="pr-5 text-right text-foreground/60">{ago(u.last_seen_at)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            {!d.users.length && <div className="p-4"><Empty>No accounts match “{term}”.</Empty></div>}
            <div className="flex items-center justify-end gap-2 border-t border-white/6 p-3">
              <button disabled={!page} onClick={() => setPage(page - 1)} className="beam h-9 rounded-full px-4 text-sm hover:bg-white/8 disabled:opacity-40">Previous</button>
              <span className="text-xs text-foreground/48">Page {page + 1} of {Math.max(1, Math.ceil(d.total / per))}</span>
              <button disabled={(page + 1) * per >= d.total} onClick={() => setPage(page + 1)} className="beam h-9 rounded-full px-4 text-sm hover:bg-white/8 disabled:opacity-40">Next</button>
            </div>
            <UserSheet id={open} onClose={() => setOpen(null)} onChanged={reload} />
          </Panel>
        )}
      </Section>
    </>
  );
}

// ---- event log ------------------------------------------------------------
function Events() {
  const { s } = useSession();
  const [kind, setKind] = useState(""), [user, setUser] = useState(""), [who, setWho] = useState("");
  const [older, setOlder] = useState<any[]>([]), [next, setNext] = useState<number | null>(null);
  useEffect(() => { const t = setTimeout(() => setWho(user.trim()), 300); return () => clearTimeout(t); }, [user]);
  const qs = `kind=${kind}&user=${encodeURIComponent(who)}`;
  useEffect(() => { setOlder([]); setNext(null); }, [qs]); // eslint-disable-line react-hooks/set-state-in-effect
  const more = async (cursor: number) => {
    const r = await adminGet<any>(`events?${qs}&before=${cursor}`, s.token);
    setOlder((o) => [...o, ...r.events]); setNext(r.next ?? -1);
  };
  const sel = "beam h-10 rounded-full bg-white/6 px-4 text-sm outline-none";
  return (
    <Section path={`events?${qs}`} title="Events" desc="The raw log: sign-ins, profile changes, pairing, rooms, playback and admin actions. Newest first.">
      {(d: any) => {
        const cursor = next === null ? d.next : next > 0 ? next : null;
        return (
          <Panel className="p-0">
            <div className="flex flex-wrap gap-3 border-b border-white/6 p-4">
              <select value={kind} onChange={(e) => setKind(e.target.value)} aria-label="Event kind" className={sel}>
                <option value="">All events</option>{Object.entries(KIND_LABEL).map(([k, l]) => <option key={k} value={k}>{k.replace(/_/g, " ")}: {l}</option>)}
              </select>
              <input value={user} onChange={(e) => setUser(e.target.value)} placeholder="Username or user id" aria-label="Filter by user" className={cn(sel, "min-w-0 flex-1 placeholder:text-foreground/40")} />
            </div>
            <div className="divide-y divide-white/6 px-5">{[...d.events, ...older].map((e: any) => <EventLine key={e.id} e={e} />)}</div>
            {!d.events.length && <div className="p-4"><Empty>No events match these filters.</Empty></div>}
            {cursor && <div className="border-t border-white/6 p-3 text-center"><button onClick={() => more(cursor)} className="beam h-9 rounded-full bg-white/6 px-5 text-sm hover:bg-white/10">Load older</button></div>}
          </Panel>
        );
      }}
    </Section>
  );
}

// ---- ops ------------------------------------------------------------------
function Ops() {
  return (
    <Section path="ops" title="Ops" desc="Cloudflare Worker errors and CPU, D1 usage against the free plan, and recent deploys. Cloudflare numbers are cached for 5 minutes.">
      {(d: any) => {
        const cf = d.cloudflare;
        const scripts = Object.values((cf?.workers ?? []).reduce((acc: any, w: any) => {
          const k = w.dimensions.scriptName, a = (acc[k] ??= { name: k, requests: 0, errors: 0, exceeded: 0, p50: 0, p99: 0 });
          a.requests += w.sum.requests; a.errors += w.sum.errors;
          if (w.dimensions.status === "exceededResources") a.exceeded += w.sum.requests;
          a.p50 = Math.max(a.p50, w.quantiles.cpuTimeP50); a.p99 = Math.max(a.p99, w.quantiles.cpuTimeP99);
          return acc;
        }, {})) as any[];
        const d1 = Object.values((cf?.d1 ?? []).reduce((acc: any, r: any) => {
          const a = (acc[r.dimensions.date] ??= { day: r.dimensions.date, read: 0, written: 0 });
          a.read += r.sum.rowsRead; a.written += r.sum.rowsWritten; return acc;
        }, {})).sort((a: any, b: any) => a.day.localeCompare(b.day)) as any[];
        const today = d1[d1.length - 1], storage = Math.max(0, ...(cf?.storage ?? []).map((s: any) => s.max.databaseSizeBytes));
        return (
          <div className="grid gap-4 lg:grid-cols-2">
            {!d.connected ? (
              <Panel title="Connect Cloudflare analytics" className="lg:col-span-2">
                <p className="max-w-[62ch] text-sm text-foreground/64">Worker errors, CPU and D1 usage come from the Cloudflare GraphQL Analytics API. Create an API token with <span className="text-foreground">Account Analytics: Read</span>, then from <code className="text-primary">server/</code> run:</p>
                <pre className="mt-3 overflow-x-auto rounded-xl bg-dusk p-4 text-xs leading-relaxed text-foreground/80">{`npx wrangler secret put CF_ANALYTICS_TOKEN\n# and in wrangler.toml [vars]:\nCF_ACCOUNT_ID = "<your account id>"`}</pre>
              </Panel>
            ) : cf?.error ? (
              <Panel title="Cloudflare analytics" className="lg:col-span-2"><Empty>Cloudflare answered: {cf.error}</Empty></Panel>
            ) : (
              <>
                <Panel title="Workers" hint="last 7 days" className="lg:col-span-2">
                  <Table>
                    <TableHeader><TableRow className="border-white/6 hover:bg-transparent"><TableHead>Worker</TableHead><TableHead className="text-right">Requests</TableHead><TableHead className="text-right">Errors</TableHead><TableHead className="text-right">CPU limit (1102)</TableHead><TableHead className="hidden text-right sm:table-cell">CPU p50 / p99</TableHead></TableRow></TableHeader>
                    <TableBody>{scripts.map((w) => (
                      <TableRow key={w.name} className="border-white/6"><TableCell className="font-medium">{w.name}</TableCell><TableCell className="text-right tabular-nums">{n(w.requests)}</TableCell>
                        <TableCell className={cn("text-right tabular-nums", w.errors && "text-[#f07c6c]")}>{n(w.errors)}</TableCell><TableCell className={cn("text-right tabular-nums", w.exceeded && "text-[#f07c6c]")}>{n(w.exceeded)}</TableCell>
                        <TableCell className="hidden text-right tabular-nums sm:table-cell">{(w.p50 / 1000).toFixed(1)} / {(w.p99 / 1000).toFixed(1)} ms</TableCell></TableRow>
                    ))}</TableBody>
                  </Table>
                </Panel>
                <Panel title="D1 today" hint="against the free plan">
                  {[["Rows read", today?.read ?? 0, d.limits.rowsReadPerDay], ["Rows written", today?.written ?? 0, d.limits.rowsWrittenPerDay], ["Storage", storage, d.limits.storageBytes]].map(([l, v, max]) => (
                    <div key={l as string} className="mb-4 last:mb-0"><div className="mb-1.5 flex justify-between text-sm"><span>{l}</span><span className="tabular-nums text-foreground/64">{l === "Storage" ? `${(Number(v) / 1e6).toFixed(1)} MB of 5 GB` : `${n(Number(v))} of ${n(Number(max))}`}</span></div><Meter v={Number(v) / Number(max)} tone={Number(v) / Number(max) > 0.8 ? "coral" : "lamp"} /></div>
                  ))}
                </Panel>
                <Panel title="D1 rows per day" hint="last 7 days">
                  <Ranked empty="No D1 data yet." rows={d1.slice().reverse().map((r) => ({ label: shortDay(r.day), value: r.written, shown: `${n(r.written)} written`, sub: `${n(r.read)} read` }))} />
                </Panel>
              </>
            )}
            <Panel title="Deploys" hint="GitHub Actions, BeyonderSS/Reelm">
              {d.deploys.runs.length ? <ul className="divide-y divide-white/6">{d.deploys.runs.map((r: any) => (
                <li key={r.id} className="flex items-center gap-3 py-2.5 text-sm"><span className={cn("size-2 shrink-0 rounded-full", r.conclusion === "success" ? "bg-signal" : r.conclusion ? "bg-[#f07c6c]" : "bg-primary")} />
                  <a href={r.url} target="_blank" rel="noreferrer" className="min-w-0 flex-1 hover:underline"><span className="block truncate font-medium">{r.title}</span><span className="text-xs text-foreground/48">{r.name}, {r.branch}</span></a><span className="shrink-0 text-xs text-foreground/48">{ago(r.at)}</span></li>
              ))}</ul> : <Empty>GitHub returned no runs.</Empty>}
            </Panel>
            <Panel title="Releases" hint="APK downloads">
              <Ranked empty="No releases yet." rows={d.deploys.releases.map((r: any) => ({ label: <a href={r.url} target="_blank" rel="noreferrer" className="hover:underline">{r.tag}</a>, value: r.downloads, shown: `${n(r.downloads)} downloads`, sub: ago(r.at) }))} />
            </Panel>
          </div>
        );
      }}
    </Section>
  );
}

const REPO = "https://github.com/Zenda-Cross/vega-providers";
function Providers() {
  const { s } = useSession();
  const [busy, setBusy] = useState(false), [sel, setSel] = useState<string[] | null>(null), [ask, setAsk] = useState(false), [hideOff, setHideOff] = useState(true);
  const run = async (reload: () => void, json: { commit?: string; enabled?: string[] }, ok: string) => {
    setBusy(true);
    try { await adminPin(s.token, json); toast.success(ok); setSel(null); setAsk(false); reload(); } catch (e: any) { toast.error(e.message ?? "Couldn't save"); } finally { setBusy(false); }
  };
  return (
    <Section path="providers" title="Providers" desc="Apps only run provider code from the commit approved here. Review upstream changes, then approve the update.">
      {(d: any, reload) => {
        const p = d.pinned, up = d.upstream, enabled: string[] = sel ?? p.enabled, behind = d.behind ?? 0;
        const toggle = (v: string) => setSel(enabled.includes(v) ? enabled.filter((x) => x !== v) : [...enabled, v]);
        return (
          <div className="grid gap-4 lg:grid-cols-2">
            {d.error && <Panel title="GitHub unavailable" className="lg:col-span-2"><Empty>{d.error}. The pinned version below is still served to apps.</Empty></Panel>}
            <Panel title="Pinned version" hint="what apps run now">
              <a href={`${REPO}/commit/${p.commit}`} target="_blank" rel="noreferrer" className="font-mono text-lg font-semibold text-primary hover:underline">{p.commit.slice(0, 7)}</a>
              <p className="mt-1 text-sm text-foreground/64">Approved by {p.approvedBy ?? "unknown"}, {ago(p.approvedAt)}</p>
            </Panel>
            {up && (
              <Panel title="Upstream" hint="latest on main" action={<a href={d.compareUrl} target="_blank" rel="noreferrer" className="text-sm text-primary hover:underline">View changes</a>}>
                <a href={`${REPO}/commit/${up.commit}`} target="_blank" rel="noreferrer" className="font-mono text-lg font-semibold hover:underline">{up.commit.slice(0, 7)}</a>
                <p className="mt-1 truncate text-sm text-foreground/64">{up.message}</p>
                <p className="mt-1 text-xs text-foreground/48">{up.date ? ago(up.date) : ""}{behind ? `, ${behind} commit${behind === 1 ? "" : "s"} behind` : ", up to date"}</p>
                {behind > 0 && <button onClick={() => setAsk(true)} disabled={busy} className="beam mt-4 h-10 rounded-full bg-primary px-5 text-sm font-semibold text-primary-foreground disabled:opacity-60">Approve update</button>}
                <Dialog open={ask} onOpenChange={(o) => !busy && setAsk(o)}>
                  <DialogContent className="rounded-[24px] border-white/8 bg-haze">
                    <DialogHeader><DialogTitle className="font-display text-xl">Approve update</DialogTitle>
                      <DialogDescription>Apps will start running provider code from {up.commit.slice(0, 7)}. Review the changes first. Approve?</DialogDescription></DialogHeader>
                    <DialogFooter>
                      <button onClick={() => setAsk(false)} disabled={busy} className="beam h-10 rounded-full px-5 text-sm hover:bg-white/8">Cancel</button>
                      <button onClick={() => run(reload, { commit: up.commit }, `Pinned ${up.commit.slice(0, 7)}`)} disabled={busy} className="beam h-10 rounded-full bg-primary px-5 text-sm font-semibold text-primary-foreground disabled:opacity-60">{busy ? "Approving" : "Approve"}</button>
                    </DialogFooter>
                  </DialogContent>
                </Dialog>
              </Panel>
            )}
            {up && (
              <Panel title="Changed providers" hint="dist/ files between pinned and upstream" className="lg:col-span-2">
                {d.changed?.length ? <ul className="divide-y divide-white/6">{d.changed.map((c: any) => (
                  <li key={c.provider} className="flex items-center gap-3 py-2.5 text-sm">
                    <span className={cn("size-2 shrink-0 rounded-full", p.enabled.includes(c.provider) ? "bg-primary" : "bg-white/20")} />
                    <span className={cn("font-medium", p.enabled.includes(c.provider) && "text-primary")}>{c.provider}</span>
                    <span className="text-xs text-foreground/48">{c.status}, {c.files.length} file{c.files.length === 1 ? "" : "s"}</span>
                    {p.enabled.includes(c.provider) && <span className="ml-auto text-xs text-primary">enabled</span>}
                  </li>))}</ul> : <Empty>No provider files changed.</Empty>}
              </Panel>
            )}
            {d.manifest && (
              <Panel title="Providers" hint={`${enabled.length} on. Apps ask #1 to #10 per episode (#1 and #2 on low-end TVs), so move your best ones up. Greyed out: switched off by the Vega maintainers`} className="lg:col-span-2"
                action={<div className="flex flex-wrap items-center gap-2 text-sm">
                  <button onClick={() => setHideOff(!hideOff)} className="beam h-9 rounded-full px-3 hover:bg-white/8">{hideOff ? "Show disabled" : "Hide disabled"}</button>
                  <button onClick={() => setSel([...enabled, ...d.manifest.filter((m: any) => !m.disabled && !enabled.includes(m.value)).map((m: any) => m.value)])} className="beam h-9 rounded-full px-3 hover:bg-white/8">Select all</button>
                  <button onClick={() => setSel([])} className="beam h-9 rounded-full px-3 hover:bg-white/8">Clear</button>
                  {sel && <button onClick={() => setSel(null)} className="beam h-9 rounded-full px-3 hover:bg-white/8">Reset</button>}
                  {sel && <button onClick={() => run(reload, { enabled }, "Enabled list saved")} disabled={busy} className="beam h-9 rounded-full bg-primary px-4 font-semibold text-primary-foreground disabled:opacity-60">{busy ? "Saving" : "Save enabled"}</button>}
                </div>}>
                {/* enabled first, in the order apps ask them (only the first 10 are asked), then the rest in manifest order */}
                <ul className="divide-y divide-white/6">{[...enabled.map((v) => d.manifest.find((m: any) => m.value === v)).filter(Boolean), ...d.manifest.filter((m: any) => !enabled.includes(m.value))]
                  .filter((m: any) => !(hideOff && m.disabled)).map((m: any) => { const at = enabled.indexOf(m.value); return (
                  <li key={m.value} className={cn("flex items-center gap-3 py-2.5 text-sm", m.disabled && "opacity-40")}>
                    <input type="checkbox" aria-label={`Enable ${m.name}`} checked={at >= 0} disabled={m.disabled} onChange={() => toggle(m.value)} className="size-4 accent-[#F2B45A]" />
                    <span className={cn("w-7 text-right text-xs tabular-nums", at >= 0 && at < 10 ? "font-semibold text-primary" : "text-foreground/40")} title={at >= 0 && at < 10 ? "Asked first" : at >= 0 ? "Enabled, but only the first 10 are asked" : undefined}>{at >= 0 ? `#${at + 1}` : ""}</span>
                    <span className="min-w-0 flex-1 truncate font-medium">{m.name}<span className="ml-2 font-mono text-xs font-normal text-foreground/48">{m.value}</span></span>
                    {at > 0 && <button onClick={() => setSel([m.value, ...enabled.filter((x) => x !== m.value)])} className="beam h-7 rounded-full px-2.5 text-xs text-foreground/72 hover:bg-white/8">Move to top</button>}
                    <span className="rounded-full bg-white/6 px-2 py-0.5 text-xs text-foreground/64">{m.type}</span>
                    <span className="hidden w-12 text-right text-xs tabular-nums text-foreground/48 sm:block">v{m.version}</span>
                  </li>); })}</ul>
              </Panel>
            )}
          </div>
        );
      }}
    </Section>
  );
}

export const SECTIONS: Record<string, () => ReactNode> = {
  "": Overview, live: Live, engagement: Engagement, content: Content, sources: Sources, app: AppHealth, security: Security, users: UsersPage, events: Events, ops: Ops, providers: Providers,
};
