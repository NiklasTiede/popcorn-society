import { authApi } from "../../../shared/api/moviesApi";
import { checkUsernameAvailability } from "./identityAvailability";

vi.mock("../../../shared/api/moviesApi", () => ({
  authApi: {
    checkUsernameAvailability: vi.fn(),
  },
}));

describe("identity availability api", () => {
  it("checks username availability through the auth api", async () => {
    vi.mocked(authApi.checkUsernameAvailability).mockResolvedValueOnce({
      data: { isAvailable: true },
    } as Awaited<ReturnType<typeof authApi.checkUsernameAvailability>>);

    await expect(checkUsernameAvailability("les_grossman")).resolves.toEqual({
      isAvailable: true,
    });
    expect(authApi.checkUsernameAvailability).toHaveBeenCalledWith(
      "les_grossman",
    );
  });
});
