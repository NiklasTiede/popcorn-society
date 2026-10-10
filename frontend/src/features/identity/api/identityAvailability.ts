import type { UserIdentityAvailability } from "../../../client/movies/generator-output";
import { authApi } from "../../../shared/api/moviesApi";

export const checkUsernameAvailability = async (
  username: string,
): Promise<UserIdentityAvailability> => {
  const response = await authApi.checkUsernameAvailability(username);
  return response.data;
};
