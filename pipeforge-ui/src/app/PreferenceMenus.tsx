/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useT, type Language } from "@/i18n";
import { usePreferences, type ThemeMode } from "@/state/preferences";
import { DropdownMenu, IconButton, type IconName } from "@/ui";

const THEME_ICONS: Record<ThemeMode, IconName> = { light: "sun", dark: "moon", system: "monitor" };

/** Theme and language menus of the top bar. */
export function PreferenceMenus() {
  const t = useT();
  const { theme, language, setTheme, setLanguage } = usePreferences();
  return (
    <div className="flex items-center gap-1">
      <DropdownMenu
        label={t("preferences.language")}
        value={language}
        onSelect={(id) => setLanguage(id as Language)}
        items={[
          { id: "en-US", label: "English" },
          { id: "zh-CN", label: "中文" },
        ]}
        trigger={(props) => <IconButton icon="globe" label={t("preferences.language")} {...props} />}
      />
      <DropdownMenu
        label={t("preferences.theme")}
        value={theme}
        onSelect={(id) => setTheme(id as ThemeMode)}
        items={(["light", "dark", "system"] as const).map((mode) => ({
          id: mode,
          label: t(`preferences.${mode}`),
          icon: THEME_ICONS[mode],
        }))}
        trigger={(props) => <IconButton icon={THEME_ICONS[theme]} label={t("preferences.theme")} {...props} />}
      />
    </div>
  );
}
