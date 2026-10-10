import "@testing-library/jest-dom/vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { SnackbarProvider } from "notistack";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router";

import * as availabilityApi from "../api/identityAvailability";
import * as identityMutations from "../api/identityMutations";
import RegistrationPage from "./RegistrationPage";

vi.mock("../api/identityAvailability", () => ({
  checkUsernameAvailability: vi.fn(),
}));
vi.mock("../api/identityMutations", async (importOriginal) => {
  const actual =
    await importOriginal<typeof import("../api/identityMutations")>();
  return { ...actual, registerAccount: vi.fn() };
});

const renderRegistrationPage = () => {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <SnackbarProvider>
        <MemoryRouter>{children}</MemoryRouter>
      </SnackbarProvider>
    </QueryClientProvider>
  );

  return render(<RegistrationPage />, { wrapper });
};

const fillValidRegistration = async (
  user: ReturnType<typeof userEvent.setup>,
) => {
  await user.type(screen.getByLabelText("Username"), "new_user");
  await user.type(screen.getByLabelText("Email"), "new@example.com");
  await user.type(screen.getByLabelText("Password"), "Movie!12");
  await user.type(screen.getByLabelText("Confirm password"), "Movie!12");
  await waitFor(() => expect(screen.getAllByText("Available")).toHaveLength(1));
  await waitFor(() =>
    expect(
      screen.getByRole("button", { name: "Create account" }),
    ).not.toHaveProperty("disabled", true),
  );
};

describe("RegistrationPage", () => {
  beforeEach(() => {
    vi.mocked(availabilityApi.checkUsernameAvailability).mockResolvedValue({
      isAvailable: true,
    });
    vi.mocked(identityMutations.registerAccount).mockResolvedValue({
      message: "Account created. You can sign in now.",
    });
  });

  it("renders the modern two-pane registration experience", () => {
    renderRegistrationPage();

    expect(
      screen.getByRole("heading", { name: "Create your account" }),
    ).toBeTruthy();
    expect(screen.getByText("Track your taste in cinema.")).toBeTruthy();
    expect(
      screen.getByRole("button", { name: "Create account" }),
    ).not.toHaveProperty("disabled", true);
    expect(
      screen.getByRole("button", { name: "Continue with Google" }),
    ).toBeTruthy();
    expect(
      screen.getByRole("button", { name: "Continue with GitHub" }),
    ).toBeTruthy();
  });

  it("shows live password rule feedback", async () => {
    const user = userEvent.setup();
    renderRegistrationPage();

    await user.type(screen.getByLabelText("Password"), "Abcdefg1");

    expect(screen.getByText("8-30 characters").getAttribute("data-met")).toBe(
      "true",
    );
    expect(
      screen.getByText("One uppercase letter").getAttribute("data-met"),
    ).toBe("true");
    expect(
      screen.getByText("One lowercase letter").getAttribute("data-met"),
    ).toBe("true");
    expect(screen.getByText("One number").getAttribute("data-met")).toBe(
      "true",
    );
    expect(
      screen.getByText("One special character").getAttribute("data-met"),
    ).toBe("false");

    await user.type(screen.getByLabelText("Password"), "!");

    expect(
      screen.getByText("One special character").getAttribute("data-met"),
    ).toBe("true");
  });

  it("disables submit when username is taken", async () => {
    const user = userEvent.setup();
    vi.mocked(availabilityApi.checkUsernameAvailability).mockResolvedValue({
      isAvailable: false,
    });
    renderRegistrationPage();

    await user.type(screen.getByLabelText("Username"), "admin");

    expect(await screen.findByText("Taken")).toBeTruthy();
    expect(
      screen.getByRole("button", { name: "Create account" }),
    ).toHaveProperty("disabled", true);
  });

  it("explains when an availability check cannot be completed", async () => {
    const user = userEvent.setup();
    vi.mocked(availabilityApi.checkUsernameAvailability).mockRejectedValue(
      new Error("network unavailable"),
    );
    renderRegistrationPage();

    await user.type(screen.getByLabelText("Username"), "movie_fan");

    expect(
      await screen.findByText(/Availability check unavailable/i),
    ).toBeTruthy();
    expect(
      screen.getByRole("button", { name: "Create account" }),
    ).not.toHaveProperty("disabled", true);
  });

  it("validates email locally without exposing account availability", async () => {
    const user = userEvent.setup();
    renderRegistrationPage();
    await user.type(screen.getByLabelText("Email"), "new@example.com");
    await user.tab();
    expect(screen.queryByText("Available")).not.toBeInTheDocument();
    expect(screen.queryByText("In use")).not.toBeInTheDocument();
  });

  it("still maps a duplicate email at registration to the field", async () => {
    const user = userEvent.setup();
    vi.mocked(identityMutations.registerAccount).mockRejectedValue({
      response: { data: { errors: { email: "Email is already used" } } },
    });
    renderRegistrationPage();
    await fillValidRegistration(user);
    await user.click(screen.getByRole("button", { name: "Create account" }));
    expect(await screen.findByText("Email is already used")).toBeTruthy();
    expect(screen.getByLabelText("Email")).toHaveFocus();
  });

  it("shows progress while account creation is pending", async () => {
    const user = userEvent.setup();
    vi.mocked(identityMutations.registerAccount).mockReturnValue(
      new Promise(() => {}),
    );
    renderRegistrationPage();

    await fillValidRegistration(user);
    await user.click(screen.getByRole("button", { name: "Create account" }));

    expect(
      screen.getByRole("button", { name: /Creating account/i }),
    ).toHaveProperty("disabled", true);
  });

  it("maps a backend username conflict to the field and focuses it", async () => {
    const user = userEvent.setup();
    vi.mocked(identityMutations.registerAccount).mockRejectedValue({
      response: {
        data: { errors: { username: "Username is already used" } },
      },
    });
    renderRegistrationPage();

    await fillValidRegistration(user);
    await user.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByText("Username is already used")).toBeTruthy();
    expect(screen.getByLabelText("Username")).toHaveFocus();
  });
});
