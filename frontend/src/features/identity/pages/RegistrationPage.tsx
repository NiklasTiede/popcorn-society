import {
  Box,
  Alert,
  Button,
  Checkbox,
  CircularProgress,
  FormControlLabel,
  Grid,
  InputAdornment,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import CancelIcon from "@mui/icons-material/Cancel";
import CheckIcon from "@mui/icons-material/Check";
import { useState } from "react";
import { useNavigate } from "react-router";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import type { RegistrationRequest } from "../model/identityRequests";
import { useMutation } from "@tanstack/react-query";
import {
  getRegistrationInvalidParams,
  registerAccount,
} from "../api/identityMutations";
import { checkUsernameAvailability } from "../api/identityAvailability";
import { authTextFieldSx } from "../components/authFormStyles";
import SocialLoginButtons from "../components/SocialLoginButtons";
import { useAvailability } from "../hooks/useAvailability";
import AuthPageFrame from "../components/AuthPageFrame";
import {
  passwordRules,
  registrationSchema,
  type RegistrationFormInputs,
} from "../model/registrationValidation";
import {
  getRegistrationFeedback,
  type AuthFeedback,
} from "../model/authFeedback";

const RegistrationPage = () => {
  const navigateTo = useNavigate();

  const [showPassword, setShowPassword] = useState(false);
  const [feedback, setFeedback] = useState<AuthFeedback | null>(null);

  const {
    setError,
    setFocus,
    register,
    handleSubmit,
    watch,
    formState: { errors },
  } = useForm<RegistrationFormInputs>({
    mode: "onBlur",
    resolver: zodResolver(registrationSchema),
  });

  const username = watch("username") ?? "";
  const password = watch("password") ?? "";

  const usernameAvailability = useAvailability({
    checkFn: checkUsernameAvailability,
    enabled: !errors.username && username.length >= 2,
    value: username,
  });
  const registerAccountMutation = useMutation({
    mutationFn: registerAccount,
    onMutate: () => setFeedback(null),
    onSuccess: (response) => {
      void navigateTo("/login", {
        state: {
          registrationMessage:
            response.message ?? "Account created. You can sign in now.",
        },
      });
    },
    onError: (error: unknown) => {
      const invalidParams = getRegistrationInvalidParams(error);

      if (invalidParams.email) {
        setError("email", {
          type: "custom",
          message: invalidParams.email,
        });
      }
      if (invalidParams.username) {
        setError("username", {
          type: "custom",
          message: invalidParams.username,
        });
      }
      if (invalidParams.username) {
        setFocus("username");
      } else if (invalidParams.email) {
        setFocus("email");
      }
      if (!invalidParams.email && !invalidParams.username) {
        setFeedback(getRegistrationFeedback());
      }
    },
  });

  const onSubmit = (data: RegistrationFormInputs) => {
    const request: RegistrationRequest = {
      username: data.username,
      email: data.email,
      password: data.password,
    };
    registerAccountMutation.mutate(request);
  };

  const canSubmit =
    !registerAccountMutation.isPending &&
    usernameAvailability.status !== "checking" &&
    usernameAvailability.status !== "taken";

  return (
    <AuthPageFrame formMaxWidth={480} variant="signup">
      <Box
        component="form"
        onSubmit={(event) => {
          void handleSubmit(onSubmit)(event);
        }}
      >
        <Box sx={{ mb: 3.5 }}>
          <Typography
            component="h1"
            sx={{
              color: "text.primary",
              fontSize: { xs: 24, sm: 26 },
              fontWeight: 500,
              mb: 0.75,
            }}
          >
            Create your account
          </Typography>
          <Typography color="text.secondary" variant="body2">
            Free, takes less than a minute.
          </Typography>
        </Box>

        {feedback && (
          <Alert severity={feedback.severity} sx={{ mb: 2.5 }}>
            {feedback.message}
          </Alert>
        )}

        <Stack spacing={2}>
          <TextField
            autoComplete="username"
            error={!!errors.username}
            fullWidth
            helperText={
              errors.username?.message ??
              (usernameAvailability.status === "error"
                ? "Availability check unavailable; we will verify when you submit."
                : " ")
            }
            label="Username"
            slotProps={{
              input: {
                endAdornment: (
                  <AvailabilityAdornment
                    status={usernameAvailability.status}
                    takenLabel="Taken"
                  />
                ),
              },
            }}
            type="text"
            {...register("username")}
            sx={authTextFieldSx}
          />

          <TextField
            autoComplete="email"
            error={!!errors.email}
            fullWidth
            helperText={errors.email?.message ?? " "}
            label="Email"
            type="email"
            {...register("email")}
            sx={authTextFieldSx}
          />

          <Box>
            <Grid container spacing={1.5}>
              <Grid size={{ xs: 12, sm: 6 }}>
                <TextField
                  autoComplete="new-password"
                  error={!!errors.password}
                  fullWidth
                  helperText={errors.password ? errors.password?.message : " "}
                  label="Password"
                  type={showPassword ? "text" : "password"}
                  {...register("password")}
                  sx={authTextFieldSx}
                />
              </Grid>
              <Grid size={{ xs: 12, sm: 6 }}>
                <TextField
                  autoComplete="new-password"
                  error={!!errors.confirmPassword}
                  fullWidth
                  helperText={
                    errors.confirmPassword
                      ? errors.confirmPassword?.message
                      : " "
                  }
                  label="Confirm password"
                  type={showPassword ? "text" : "password"}
                  {...register("confirmPassword")}
                  sx={authTextFieldSx}
                />
              </Grid>
            </Grid>

            <PasswordRules password={password} />

            <FormControlLabel
              control={
                <Checkbox
                  checked={showPassword}
                  onChange={() => setShowPassword((current) => !current)}
                  size="small"
                />
              }
              label="Show passwords"
              sx={{
                mt: 1,
                "& .MuiFormControlLabel-label": {
                  color: "text.secondary",
                  fontSize: 13,
                },
              }}
            />
          </Box>

          <Button
            disabled={!canSubmit}
            fullWidth
            sx={{ py: 1.5, textTransform: "none" }}
            type="submit"
            variant="contained"
          >
            {registerAccountMutation.isPending ? (
              <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                <CircularProgress color="inherit" size={16} />
                <span>Creating account...</span>
              </Stack>
            ) : (
              "Create account"
            )}
          </Button>

          <SocialLoginButtons />
        </Stack>
      </Box>
    </AuthPageFrame>
  );
};

type AvailabilityAdornmentProps = {
  status: "idle" | "checking" | "available" | "taken" | "error";
  takenLabel: string;
};

const AvailabilityAdornment = ({
  status,
  takenLabel,
}: AvailabilityAdornmentProps) => {
  if (status === "idle" || status === "error") {
    return null;
  }

  const color =
    status === "available"
      ? "success.main"
      : status === "taken"
        ? "error.main"
        : "text.secondary";
  const label =
    status === "available"
      ? "Available"
      : status === "taken"
        ? takenLabel
        : "Checking...";

  return (
    <InputAdornment position="end">
      <Stack direction="row" spacing={0.5} sx={{ alignItems: "center" }}>
        {status === "checking" && <CircularProgress size={14} />}
        {status === "available" && <CheckIcon sx={{ color, fontSize: 16 }} />}
        {status === "taken" && <CancelIcon sx={{ color, fontSize: 16 }} />}
        <Typography sx={{ color, fontSize: 12 }}>{label}</Typography>
      </Stack>
    </InputAdornment>
  );
};

const PasswordRules = ({ password }: { password: string }) => (
  <Box
    aria-label="Password requirements"
    aria-live="polite"
    component="ul"
    sx={{
      display: "grid",
      gap: "4px 12px",
      gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr" },
      m: 0,
      mt: 1,
      p: 0,
    }}
  >
    {passwordRules.map((rule) => {
      const met = rule.met(password);
      return (
        <Stack
          component="li"
          direction="row"
          key={rule.label}
          spacing={0.75}
          sx={{ alignItems: "center", listStyle: "none" }}
        >
          <Box
            sx={{
              alignItems: "center",
              bgcolor: met ? "success.main" : "transparent",
              border: "1px solid",
              borderColor: met ? "success.main" : "line.control",
              borderRadius: "50%",
              color: "text.primary",
              display: "inline-flex",
              height: 12,
              justifyContent: "center",
              width: 12,
            }}
          >
            {met && <CheckIcon sx={{ fontSize: 9 }} />}
          </Box>
          <Typography
            component="span"
            data-met={met ? "true" : "false"}
            sx={{
              color: met ? "success.main" : "text.secondary",
              fontSize: 11,
            }}
          >
            {rule.label}
          </Typography>
        </Stack>
      );
    })}
  </Box>
);

export default RegistrationPage;
