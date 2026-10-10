# frozen_string_literal: true

require "yaml"

repository_root = File.expand_path("../../../..", __dir__)
ci = File.read(File.join(repository_root, ".github/workflows/continuous-integration.yaml"))
release = File.read(File.join(repository_root, ".github/workflows/continuous-deployment.yaml"))

def assert_contract(condition, message)
  raise message unless condition
end

assert_contract(
  ci.match?(/on:\n  pull_request:\n    branches:\n      - master\n/),
  "CI must verify pull requests targeting master"
)
assert_contract(
  ci.match?(/  merge_group:\n    types:\n      - checks_requested\n/),
  "CI must verify merge queue candidates"
)
assert_contract(
  ci.match?(/  push:\n    branches:\n      - master\n    paths:\n      - VERSION\n/),
  "CI must verify the exact VERSION commit before release"
)
assert_contract(
  !ci.match?(/^  workflow_dispatch:/),
  "the artifact-producing CI workflow must not accept manually selected source revisions"
)
assert_contract(
  ci.include?('group: ${{ github.workflow }}-${{ github.event.pull_request.number || github.ref }}') &&
    ci.include?("cancel-in-progress: true"),
  "CI must cancel superseded pull-request runs"
)
%w[branch-name backend-build-test frontend-build-test agent-build-test infrastructure-validate].each do |job|
  assert_contract(ci.match?(/^  #{Regexp.escape(job)}:$/), "CI is missing required job #{job}")
end
backend_job = ci[/^  backend-build-test:\n(.*?)(?=^  [a-z][a-z0-9-]*:\n)/m, 1]
assert_contract(!backend_job.nil?, "CI backend job could not be parsed")
assert_contract(
  backend_job.scan(/run: \.\/gradlew/).length == 1 &&
    backend_job.include?("./gradlew build jacocoTestReport copyPyroscopeAgent"),
  "CI backend verification must use one complete Gradle build invocation"
)
%w[
  docker-build-backend-prebuilt
  container-smoke-backend
  docker-build-frontend-prebuilt
  container-smoke-frontend
].each do |target|
  assert_contract(
    ci.match?(/^\s+run: make #{Regexp.escape(target)}$/),
    "CI must run the #{target} runtime image gate"
  )
end

%w[backend frontend agent].each do |service|
  assert_contract(
    ci.include?("name: release-#{service}-${{ github.sha }}") &&
      ci.include?("path: /tmp/#{service}-image.tar.gz"),
    "CI must export the verified #{service} image for its exact commit"
  )
end
assert_contract(
  ci.scan("uses: actions/upload-artifact@v7").length == 3 &&
    ci.scan("if: github.event_name == 'push'").length == 6 &&
    ci.scan("retention-days: 1").length == 3 &&
    ci.scan("compression-level: 0").length == 3,
  "release images must use short-lived uncompressed artifact uploads"
)

release_document = YAML.load_file(File.join(repository_root, ".github/workflows/continuous-deployment.yaml"))
release_job = release_document.fetch("jobs").fetch("release-app")
assert_contract(
  release_document.fetch("permissions") == { "contents" => "read" },
  "workflow default permissions must be read-only"
)
assert_contract(
  release_job.fetch("permissions") == {
    "actions" => "read", "contents" => "write", "pull-requests" => "write"
  },
  "release job must request only artifact-read, ref-write, and PR-write scopes"
)
steps = release_job.fetch("steps")
steps.select { |step| step.key?("uses") }.each do |step|
  assert_contract(
    step.fetch("uses").match?(/\A[\w.-]+\/[\w.\/-]+@[0-9a-f]{40}\z/),
    "release actions must pin full immutable upstream commit SHAs: #{step.fetch("name")}"
  )
end
checkout = steps.find { |step| step.fetch("uses", "").start_with?("actions/checkout@") }
assert_contract(
  checkout&.dig("with", "persist-credentials") == false,
  "checkout must not persist write credentials across build and validation steps"
)
git_steps = steps.select { |step| step.fetch("run", "").match?(/\bgit push\b/) }
assert_contract(git_steps.length == 2, "only deployment branch and tag steps may push")
git_steps.each do |step|
  env = step.fetch("env")
  assert_contract(
    env["GH_TOKEN"] == "${{ secrets.GITHUB_TOKEN }}" &&
      env["GIT_TERMINAL_PROMPT"] == "0" &&
      env["GIT_CONFIG_COUNT"] == "1" &&
      env["GIT_CONFIG_KEY_0"] == "credential.helper" &&
      env["GIT_CONFIG_VALUE_0"] == '!f() { printf "%s\n" "username=x-access-token" "password=$GH_TOKEN"; }; f',
    "Git pushes must use a transient credential helper with step-scoped authentication"
  )
end
assert_contract(
  (steps - git_steps).all? { |step| !step.fetch("env", {}).key?("GIT_CONFIG_VALUE_0") },
  "Git push credentials must not persist into other release steps"
)

assert_contract(release.include?("contents: write"), "release requires contents write permission")
assert_contract(
  release.include?("pull-requests: write"),
  "release requires pull-request write permission"
)
assert_contract(release.include?("actions: read"), "release requires artifact read permission")
assert_contract(
  release.match?(/workflow_run:\n    workflows:\n      - CI - Build \/ Test Deployables\n    branches:\n      - master\n    types:\n      - completed/),
  "release must start only after the master CI workflow completes"
)
assert_contract(
  release.include?("github.event.workflow_run.conclusion == 'success'") &&
    release.include?("github.event.workflow_run.event == 'push'"),
  "automatic release must require a successful push CI run"
)
assert_contract(
  release.include?("ref: ${{ github.event_name == 'workflow_run' && github.event.workflow_run.head_sha || github.sha }}"),
  "release must check out the exact CI commit"
)
%w[backend frontend agent].each do |service|
  assert_contract(
    release.include?("name: release-#{service}-${{ github.event.workflow_run.head_sha }}") &&
      release.include?("path: ${{ runner.temp }}/release-images/#{service}") &&
      release.include?("gzip -dc \"$RUNNER_TEMP/release-images/#{service}/#{service}-image.tar.gz\" | docker load"),
    "release must load the verified #{service} image artifact"
  )
end
assert_contract(
  release.include?('test -f "$archive"') &&
    release.include?('test ! -L "$archive"') &&
    release.include?('gzip -t "$archive"'),
  "release image archives must be regular, non-linked, valid gzip files before loading"
)
assert_contract(
  release.scan(/uses: actions\/download-artifact@[0-9a-f]{40} # v8/).length == 3 &&
    release.scan("run-id: ${{ github.event.workflow_run.id }}").length == 3,
  "release must download all images from the triggering CI run"
)
%w[
  Set\ up\ JDK
  Build\ and\ test\ backend
  Set\ up\ Node.js
  Install\ frontend\ dependencies
  Generate\ frontend\ API\ client
  Lint\ frontend
  Test\ frontend
  Build\ frontend
  Install\ uv\ and\ Python\ 3.14
  Install\ locked\ agent\ dependencies
  Verify\ agent
  Build\ manual\ release\ images
  Smoke-test\ manual\ release\ images
].each do |escaped_name|
  step_name = escaped_name.tr("\\", "")
  assert_contract(
    release.match?(/- name: #{escaped_name}\n\s+if: github\.event_name == 'workflow_dispatch'/),
    "#{step_name} must run only for manual releases"
  )
end
assert_contract(
  !release.include?("docker buildx build"),
  "automatic releases must promote images instead of rebuilding them"
)
assert_contract(
  release.include?("group: versioned-app-release") &&
    release.include?("cancel-in-progress: false"),
  "releases must be serialized without cancellation"
)
assert_contract(
  release.include?('branch=release/v${version}-deployment'),
  "release must create a version-specific deployment branch"
)
%w[make\ verify-agent popcorn-society-agent APP_VERSION agent.yaml].each do |contract|
  assert_contract(
    release.include?(contract.tr("\\", "")),
    "release agent contract is missing #{contract}"
  )
end
assert_contract(
  release.include?("AGENT_APP_VERSION=\"${{ steps.version.outputs.value }}\""),
  "agent build metadata must use the unprefixed release version"
)
assert_contract(
  release.include?("EXPECTED_APP_VERSION: ${{ steps.version.outputs.value }}"),
  "release must strictly verify every published image version"
)
assert_contract(
  release.include?('git push --set-upstream origin "$RELEASE_BRANCH"'),
  "release must push manifests to a deployment branch"
)
assert_contract(
  release.include?("gh pr create") &&
    release.include?("--base master") &&
    release.include?('--head "$RELEASE_BRANCH"'),
  "release must open a deployment pull request"
)
push_commands = release.lines.grep(/\bgit push\b/).map(&:strip)
expected_push_commands = [
  'git push --set-upstream origin "$RELEASE_BRANCH"',
  'git push origin "${{ steps.version.outputs.tag }}"'
]
assert_contract(
  push_commands.sort == expected_push_commands.sort,
  "release may push only its deployment branch and annotated tag"
)
assert_contract(!release.include?("[skip ci]"), "deployment commits must run pull-request CI")
assert_contract(
  release.include?('git tag -a "${{ steps.version.outputs.tag }}" "${{ steps.source.outputs.sha }}"'),
  "release tag must point to the exact CI source commit"
)

tag_step = release.index("- name: Create release tag")
pull_request_step = release.index("- name: Create deployment pull request")
assert_contract(!tag_step.nil? && !pull_request_step.nil?, "release completion steps are missing")
assert_contract(
  tag_step < pull_request_step,
  "release tag must exist before the deployment pull request is offered"
)

assert_contract(release.include?("python3 scripts/update-release-manifests.py"),
  "release must update images and runtime names together")
%w[backend frontend agent].each do |service|
  assert_contract(release.include?("/popcorn-society-#{service}:"),
    "new releases must publish the renamed #{service} repository")
end

puts "Release workflow contracts passed."
