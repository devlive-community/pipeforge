/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { Link } from "react-router";
import { useT } from "@/i18n";
import { buttonClass, Card, Empty } from "@/ui";

/** Shown for addresses that do not belong to any page. */
export function NotFoundPage() {
  const t = useT();
  return (
    <Card>
      <Empty
        title={t("notFound.title")}
        description={t("notFound.description")}
        action={
          <Link to="/" className={buttonClass("primary")}>
            {t("notFound.back")}
          </Link>
        }
      />
    </Card>
  );
}
