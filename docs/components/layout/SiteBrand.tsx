/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { SiteLink } from "@/components/layout/SiteLink";
import { t, type Lang } from "@/lib/i18n";
import { BRAND_MARK } from "@/lib/site";

export function SiteBrand({ lang }: { lang: Lang }) {
  return (
    <SiteLink href={`/${lang}/`} className="site-brand">
      <img src={BRAND_MARK} alt="" width={32} height={32} className="site-mark" />
      {t(lang).siteName}
    </SiteLink>
  );
}
