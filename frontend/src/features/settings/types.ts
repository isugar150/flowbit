/** Response of GET /api/v1/workspace and the admin workspace endpoints. */
export type Workspace = {
  name: string;
  logoUrl: string | null;
};
