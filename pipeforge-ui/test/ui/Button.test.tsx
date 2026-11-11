/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Badge, Button, Card, DescriptionList, Empty, PageHeader, Skeleton } from "@/ui";

describe("Button", () => {
  it("clicks, and is disabled and busy while loading", async () => {
    const onClick = vi.fn();
    const { rerender } = render(<Button onClick={onClick}>Save</Button>);
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(onClick).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button")).toHaveAttribute("type", "button");

    rerender(
      <Button onClick={onClick} loading variant="primary">
        Save
      </Button>,
    );
    const button = screen.getByRole("button", { name: "Save" });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute("aria-busy", "true");
    await userEvent.click(button);
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("uses only semantic token classes", () => {
    render(
      <>
        <Button variant="danger" size="lg">
          Delete
        </Button>
        <Button variant="ghost" size="sm" icon="refresh">
          Retry
        </Button>
      </>,
    );
    expect(screen.getByRole("button", { name: "Delete" }).className).toContain("bg-danger");
    expect(screen.getByRole("button", { name: "Retry" }).className).toContain("h-control-sm");
  });
});

describe("display components", () => {
  it("render their content", () => {
    render(
      <>
        <PageHeader title="Overview" description="What is installed" actions={<Badge tone="success">Live</Badge>} />
        <Card title="Server" description="Where the API runs">
          <DescriptionList items={[{ label: "Version", value: "0.1.0" }]} />
        </Card>
        <Empty title="Nothing here" description="Add something" />
        <Skeleton className="h-4" />
      </>,
    );
    expect(screen.getByRole("heading", { level: 1, name: "Overview" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Server" })).toBeInTheDocument();
    expect(screen.getByText("Version").tagName).toBe("DT");
    expect(screen.getByText("0.1.0").tagName).toBe("DD");
    expect(screen.getByText("Live")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Nothing here" })).toBeInTheDocument();
    expect(document.querySelector('[aria-hidden="true"].animate-pulse-soft')).not.toBeNull();
  });
});
