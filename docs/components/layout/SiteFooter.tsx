/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { SiteLink } from "@/components/layout/SiteLink";
import { CURRENT_VERSION } from "@/lib/content-core.mjs";
import { t, type Lang } from "@/lib/i18n";
import { Icon } from "../ui/Icon";
import { cx } from "../ui/cx";
import { SiteBrand } from "./SiteBrand";
import { REPOSITORY_URL } from "./SiteHeader";

export function SiteFooter({ lang, version }: { lang: Lang; version?: string }) {
  const dict = t(lang);
  return (
    <footer className="site-footer">
      <div className={cx(version ? "docs-container" : "site-container", "site-footer-inner")}>
        <div>
          <SiteBrand lang={lang} />
          <p>© 2026 devlive-community/pipeforge</p>
        </div>
        <nav aria-label={dict.exploreDocs}>
          <SiteLink href={`/${lang}/${version ?? CURRENT_VERSION}/`}>{dict.docs}</SiteLink>
          <a href={`${REPOSITORY_URL}/blob/dev/LICENSE`}>MIT License</a>
          <a href={REPOSITORY_URL}>
            GitHub <Icon name="arrowUpRight" size={14} />
          </a>
        </nav>
      </div>
    </footer>
  );
}
