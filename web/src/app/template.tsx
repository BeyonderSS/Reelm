// overflow-x-clip: the tilted 35mm .strip bleeds past the edges on purpose; clip (unlike hidden) makes no scroll container, so the page never scrolls sideways
export default function Template({ children }: { children: React.ReactNode }) {
  return <div className="page-in overflow-x-clip">{children}</div>;
}
