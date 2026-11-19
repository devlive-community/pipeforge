/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { BASE_PATH } from "@/lib/site";

export function ConnectorMark({ connector }: { connector: "mysql" | "postgresql" }) {
  return <img src={`${BASE_PATH}/connectors/${connector}.svg`} alt="" width={24} height={24} />;
}
