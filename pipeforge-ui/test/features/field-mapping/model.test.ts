/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import {
  applyCase,
  compatibility,
  deriveRows,
  freeName,
  inputStates,
  type SchemaColumn,
} from "@/features/field-mapping/model";
import type { MapperOptions } from "@/features/field-mapping/yamlEdit";

const COLUMNS: SchemaColumn[] = [
  { name: "id", type: "INT64", nullable: false },
  { name: "customerName", type: "STRING(40)" },
  { name: "amount", type: "DECIMAL(10, 2)" },
  { name: "password", type: "STRING" },
];

const options = (change: Partial<MapperOptions>): MapperOptions => ({
  entries: [],
  drop: [],
  others: "keep",
  unmapped: "fail",
  nameCase: "keep",
  order: "source",
  ...change,
});

describe("deriveRows", () => {
  it("follows the input order, puts entries with from in place and computed ones last", () => {
    const rows = deriveRows(
      options({
        entries: [
          { from: "id", to: "order_id" },
          { expr: "UPPER(customerName)", to: "loud" },
          { from: "amount", expr: "amount * 100", to: "cents" },
        ],
        drop: ["password"],
        nameCase: "snake",
      }),
      COLUMNS,
    );
    expect(rows.map((r) => r.name)).toEqual(["order_id", "customer_name", "cents", "loud"]);
    expect(rows.map((r) => r.source)).toEqual(["column", "column", "expression", "expression"]);
    expect(rows.map((r) => r.entry)).toEqual([0, null, 2, 1]);
    expect(rows[1]?.input).toBe("customerName");
  });

  it("orders by mapping, drops other columns and marks unknown columns", () => {
    const opts = options({
      entries: [{ value: "1", to: "one" }, { from: "AMOUNT" }, { from: "nope", to: "x" }],
      order: "mapping",
      others: "drop",
    });
    const rows = deriveRows(opts, COLUMNS);
    expect(rows.map((r) => r.name)).toEqual(["one", "amount", "x"]);
    expect(rows[1]?.input).toBe("amount");
    expect(rows[2]?.missing).toBe("nope");
    expect(deriveRows({ ...opts, unmapped: "drop" }, COLUMNS).map((r) => r.name)).toEqual(["one", "amount"]);
    expect([...inputStates(opts, COLUMNS, rows)]).toEqual([
      ["id", "unused"],
      ["customerName", "unused"],
      ["amount", "mapped"],
      ["password", "unused"],
    ]);
  });

  it("finds free names", () => {
    const rows = deriveRows(options({}), COLUMNS);
    expect(freeName(rows, "amount")).toBe("amount_2");
    expect(freeName(rows, "total")).toBe("total");
  });
});

describe("applyCase", () => {
  it("writes names like the plugin", () => {
    expect(applyCase("snake", "HTTPServerURL2")).toBe("http_server_url2");
    expect(applyCase("camel", "ORDER_ID")).toBe("orderId");
    expect(applyCase("upper", "created_at")).toBe("CREATED_AT");
    expect(applyCase("snake", "_")).toBe("_");
  });
});

describe("compatibility", () => {
  it.each([
    ["INT32", "INT32 NOT NULL", "same"],
    ["INT32", "INT64", "widens"],
    ["INT64", "INT16", "narrows"],
    ["INT32", "DECIMAL(12, 2)", "widens"],
    ["INT64", "DECIMAL(10, 0)", "narrows"],
    ["DECIMAL(10, 2)", "DECIMAL(14, 2)", "widens"],
    ["DECIMAL(10, 2)", "DECIMAL(10, 1)", "narrows"],
    ["STRING(20)", "STRING", "widens"],
    ["STRING", "VARCHAR(10)", "narrows"],
    ["TIMESTAMP(3)", "TIMESTAMP(6)", "widens"],
    ["DATE", "TIMESTAMP(3)", "widens"],
    ["DECIMAL(10, 2)", "INT64", "narrows"],
    ["STRING", "INT64", "converts"],
    ["INT64", "STRING", "converts"],
  ])("%s to %s %s", (from, to, expected) => {
    expect(compatibility(from, to)).toBe(expected);
  });
});
