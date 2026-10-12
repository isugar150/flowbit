import { Wordmark } from "@/components/brand/wordmark";

/** Page frame shared by the screens people see before they are signed in. */
export function AuthShell({
  title,
  description,
  children,
}: {
  title: string;
  description: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex min-h-dvh flex-col">
      <header className="flex h-14 items-center px-5 sm:px-8">
        <Wordmark />
      </header>
      <main className="flex flex-1 justify-center px-5 pt-[6vh] pb-16 sm:pt-[12vh]">
        <div className="flex w-full max-w-95 flex-col gap-7">
          <div className="flex flex-col gap-1.5">
            <h1 className="text-xl leading-7 font-semibold">{title}</h1>
            <p className="text-muted-foreground">{description}</p>
          </div>
          {children}
        </div>
      </main>
    </div>
  );
}
